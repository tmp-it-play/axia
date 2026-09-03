package team.themoment.axia.scheduling

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import team.themoment.axia.integration.github.GitHubApiException
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.query
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

class JobLeaseLost : CancellationException("Background job lease is no longer owned")

/** Fence each projection transaction before changing state, after any slow external request. */
class JobLease internal constructor(
    val name: String,
    val owner: String,
    val fencingToken: Long,
) {
    fun requireOwned(
        connection: Connection,
        now: Instant,
    ) {
        check(!connection.autoCommit)
        val valid =
            connection
                .query(
                    "SELECT 1 FROM scheduler_leases WHERE job_name=? AND owner_id=? AND fencing_token=? AND lease_until>? FOR UPDATE",
                    name,
                    owner,
                    fencingToken,
                    now,
                ) { it.getInt(1) }
                .isNotEmpty()
        if (!valid) throw JobLeaseLost()
    }
}

class MySqlJobScheduler(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val owner = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registered = mutableSetOf<String>()

    fun register(
        name: String,
        interval: Duration,
        jitter: Boolean = false,
        runImmediately: Boolean = true,
        action: suspend (JobLease) -> Unit,
    ): Job {
        require(interval >= Duration.ofSeconds(1))
        require(name.matches(Regex("[a-z0-9-]{1,128}")))
        check(registered.add(name)) { "Job already registered: $name" }
        return scope.launch {
            var firstRun = true
            var acquisitionFailures = 0
            if (!runImmediately) delay(interval.toMillis())
            while (isActive) {
                var pause = minOf(interval.toMillis(), 30_000L)
                try {
                    if (firstRun && runImmediately) requestRun(name)
                    firstRun = false
                    val lease = acquire(name)
                    acquisitionFailures = 0
                    if (lease != null) runLeased(lease, interval, jitter, action)
                } catch (cancelled: CancellationException) {
                    if (cancelled !is JobLeaseLost) throw cancelled
                } catch (failure: Exception) {
                    // No exception message/stack: upstream exceptions may retain provider data.
                    logger.error("Background job failed name={} type={}", name, failure.javaClass.simpleName)
                    // The database may itself be unavailable, so acquisition failures cannot be persisted there.
                    acquisitionFailures = (acquisitionFailures + 1).coerceAtMost(MAX_FAILURE_COUNT)
                    pause = failureDelay(acquisitionFailures).toMillis()
                }
                delay(pause)
            }
        }
    }

    /** A durable wake-up survives another instance owning the current execution. */
    suspend fun requestRun(
        name: String,
        recovered: Boolean = false,
    ): Unit =
        withContext(Dispatchers.IO) {
            require(name.matches(Regex("[a-z0-9-]{1,128}")))
            dataSource.inTransaction {
                execute(
                    "INSERT INTO scheduler_requests(job_name,pending,bypass_failure_backoff) VALUES(?,TRUE,?) " +
                        "ON DUPLICATE KEY UPDATE pending=TRUE,bypass_failure_backoff=(bypass_failure_backoff OR ?)",
                    name,
                    recovered,
                    recovered,
                )
            }
        }

    private suspend fun runLeased(
        lease: JobLease,
        interval: Duration,
        jitter: Boolean,
        action: suspend (JobLease) -> Unit,
    ) = supervisorScope {
        // Await observes the actual result without a child failure cancelling the persistence of retry state.
        val work = async { action(lease) }
        val renewal =
            launch {
                try {
                    while (work.isActive) {
                        delay(30_000)
                        if (!renew(lease)) {
                            work.cancel(JobLeaseLost())
                            break
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logger.error("Background lease renewal failed name={} type={}", lease.name, failure.javaClass.simpleName)
                    work.cancel(JobLeaseLost())
                }
            }
        var outcome = RunOutcome.CANCELLED
        var providerRetryAt: Instant? = null
        try {
            work.await()
            outcome = RunOutcome.SUCCEEDED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            outcome = RunOutcome.FAILED
            providerRetryAt = (failure as? GitHubApiException)?.retryAt
            logger.error("Background job failed name={} type={}", lease.name, failure.javaClass.simpleName)
        } finally {
            withContext(NonCancellable) {
                // Shutdown must join the action's finally blocks before releasing its lease or closing the database.
                work.cancelAndJoin()
                renewal.cancelAndJoin()
                finish(lease, interval, jitter, outcome, providerRetryAt)
            }
        }
    }

    private suspend fun acquire(name: String): JobLease? =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction {
                val now = clock.instant()
                execute(
                    "INSERT IGNORE INTO scheduler_leases(job_name,owner_id,fencing_token,lease_until,next_run_at) VALUES(?,?,0,?,?)",
                    name,
                    owner,
                    now,
                    now,
                )
                query("SELECT job_name FROM scheduler_leases WHERE job_name=? FOR UPDATE", name) { it.getString(1) }
                val request =
                    query("SELECT pending,bypass_failure_backoff FROM scheduler_requests WHERE job_name=? FOR UPDATE", name) {
                        it.getBoolean(1) to it.getBoolean(2)
                    }.singleOrNull()
                val acquired =
                    execute(
                        "UPDATE scheduler_leases SET owner_id=?,fencing_token=fencing_token+1,lease_until=? " +
                            "WHERE job_name=? AND lease_until<=? AND (next_run_at<=? OR (? AND (consecutive_failures=0 OR ?)))",
                        owner,
                        now.plus(LEASE_DURATION),
                        name,
                        now,
                        now,
                        request?.first == true,
                        request?.second == true,
                    )
                if (acquired == 0) {
                    null
                } else {
                    execute("UPDATE scheduler_requests SET pending=FALSE,bypass_failure_backoff=FALSE WHERE job_name=?", name)
                    query(
                        "SELECT fencing_token FROM scheduler_leases WHERE job_name=?",
                        name,
                    ) { JobLease(name, owner, it.getLong(1)) }.single()
                }
            }
        }

    private suspend fun renew(lease: JobLease): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction {
                val now = clock.instant()
                execute(
                    "UPDATE scheduler_leases SET lease_until=? WHERE job_name=? AND owner_id=? AND fencing_token=? AND lease_until>?",
                    now.plus(LEASE_DURATION),
                    lease.name,
                    lease.owner,
                    lease.fencingToken,
                    now,
                ) == 1
            }
        }

    private suspend fun finish(
        lease: JobLease,
        interval: Duration,
        jitter: Boolean,
        outcome: RunOutcome,
        providerRetryAt: Instant?,
    ): Unit =
        withContext(NonCancellable + Dispatchers.IO) {
            dataSource.inTransaction {
                val now = clock.instant()
                val previousFailures =
                    query(
                        "SELECT consecutive_failures FROM scheduler_leases " +
                            "WHERE job_name=? AND owner_id=? AND fencing_token=? AND lease_until>? FOR UPDATE",
                        lease.name,
                        lease.owner,
                        lease.fencingToken,
                        now,
                    ) { it.getInt(1) }.singleOrNull() ?: return@inTransaction
                val failures =
                    when (outcome) {
                        RunOutcome.SUCCEEDED -> 0
                        RunOutcome.FAILED -> (previousFailures + 1).coerceAtMost(MAX_FAILURE_COUNT)
                        RunOutcome.CANCELLED -> previousFailures
                    }
                val nextRun =
                    when (outcome) {
                        RunOutcome.SUCCEEDED -> {
                            val extra = if (jitter) Random.nextLong(0, maxOf(1, interval.toMillis() / 10)) else 0
                            now.plus(interval).plusMillis(extra)
                        }

                        RunOutcome.FAILED -> {
                            maxOf(now.plus(failureDelay(failures)), providerRetryAt ?: now)
                        }

                        RunOutcome.CANCELLED -> {
                            now
                        }
                    }
                execute(
                    "UPDATE scheduler_leases SET lease_until=?,next_run_at=?,consecutive_failures=? " +
                        "WHERE job_name=? AND owner_id=? AND fencing_token=?",
                    now,
                    nextRun,
                    failures,
                    lease.name,
                    lease.owner,
                    lease.fencingToken,
                )
            }
        }

    override fun close() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
    }

    private companion object {
        val LEASE_DURATION: Duration = Duration.ofMinutes(2)
        const val MAX_FAILURE_COUNT = 16
        val logger = LoggerFactory.getLogger(MySqlJobScheduler::class.java)
    }

    private enum class RunOutcome { SUCCEEDED, FAILED, CANCELLED }
}

/** Always jitter failed runs, independently of the optional normal-period jitter. */
internal fun failureDelay(
    consecutiveFailures: Int,
    sample: Double = Random.nextDouble(),
): Duration {
    require(consecutiveFailures > 0)
    require(sample in 0.0..1.0)
    val nominalMillis = (30_000L shl (consecutiveFailures - 1).coerceAtMost(16)).coerceAtMost(1_800_000L)
    return Duration.ofMillis((nominalMillis * (0.5 + sample)).toLong().coerceAtMost(1_800_000L))
}
