package team.themoment.axia.integration.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import team.themoment.axia.auth.store.GitHubLinkApproval
import team.themoment.axia.auth.store.JdbcAuthStore
import team.themoment.axia.auth.store.ProjectedGitHubOutcome
import team.themoment.axia.authorization.lockMemberManagement
import team.themoment.axia.persistence.execute
import team.themoment.axia.persistence.inTransaction
import team.themoment.axia.persistence.instant
import team.themoment.axia.persistence.query
import team.themoment.axia.products.IntegrationStore
import team.themoment.axia.products.ProjectIntegration
import team.themoment.axia.products.ProjectSemanticField
import team.themoment.axia.products.RepositoryIntegration
import team.themoment.axia.scheduling.JobLease
import team.themoment.axia.scheduling.ProviderHealthStore
import team.themoment.axia.tasks.TaskKind
import team.themoment.axia.tasks.TaskProjectContext
import team.themoment.axia.tasks.TaskStatus
import team.themoment.axia.tasks.TaskStore
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

data class GitHubRepositoryProjection(
    val repository: RepositoryIntegration,
    val tasks: List<GitHubTaskSnapshot>,
)

data class GitHubBoardProjection(
    val project: ProjectIntegration,
    val snapshot: GitHubProjectSnapshot,
    val requestedAt: Instant,
    val drafts: List<Pair<GitHubTaskSnapshot, TaskProjectContext>>,
    val contexts: Map<String, TaskProjectContext>,
)

data class GitHubProjectionBatch(
    val repositories: List<GitHubRepositoryProjection> = emptyList(),
    val boards: List<GitHubBoardProjection> = emptyList(),
    val memberships: List<GitHubMembershipProjection> = emptyList(),
    val unavailableBoards: List<GitHubUnavailableBoardProjection> = emptyList(),
)

data class GitHubUnavailableBoardProjection(
    val project: ProjectIntegration,
    val requestedAt: Instant,
)

data class GitHubMembershipProjection(
    val userId: Long,
    val profileVersion: Long,
    val githubId: Long,
    val requestedAt: Instant,
    val outcome: ProjectedGitHubOutcome,
)

/** Webhooks and reconciliation converge on the same transaction-aware projection/credit path. */
class GitHubSyncService(
    private val dataSource: DataSource,
    private val integrations: IntegrationStore,
    private val github: GitHubWorkClient,
    private val adapter: ProjectBoardAdapter,
    private val tasks: TaskStore,
    private val authStore: JdbcAuthStore,
    private val identity: GitHubIdentityClient,
    private val health: ProviderHealthStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun fullReconciliationDue(interval: Duration = Duration.ofHours(6)): Boolean =
        database(readOnly = true) {
            require(interval > Duration.ZERO)
            query("SELECT last_full_completed_at FROM github_sync_state WHERE id=1") { it.instant("last_full_completed_at") }
                .singleOrNull()
                ?.plus(interval)
                ?.isAfter(clock.instant()) != true
        }

    /** Schedule only one GitHub reconciliation job; its lease fences incremental and full work alike. */
    suspend fun reconcile(
        full: Boolean,
        lease: JobLease? = null,
    ) {
        val provider = health.current()
        if (provider.retryAt?.isAfter(clock.instant()) == true) return
        val authoritative = full || provider.readOnly
        val recoveryGeneration = if (provider.readOnly) health.beginRecovery() else null
        try {
            val repositories = integrations.repositories().filter { it.enabled }
            val installed = github.installationRepositories().associateBy { it.requiredText("node_id") }
            repositories.forEach { repository ->
                val remote = installed[repository.nodeId]
                if (remote == null || remote.boolean("archived") || remote.boolean("fork") || remote.boolean("is_template")) {
                    database {
                        lease?.requireOwned(this, clock.instant())
                        execute(
                            "UPDATE repository_integrations SET enabled=FALSE,access_state='UNAVAILABLE' WHERE id=? AND github_node_id=?",
                            repository.id,
                            repository.nodeId,
                        )
                    }
                    return@forEach
                }
                val canonicalRepository = repository.copy(name = remote.requiredText("name"))
                if (canonicalRepository.name != repository.name) {
                    database {
                        lease?.requireOwned(this, clock.instant())
                        execute(
                            "UPDATE repository_integrations SET name=? WHERE id=? AND github_node_id=?",
                            canonicalRepository.name,
                            repository.id,
                            repository.nodeId,
                        )
                    }
                }
                val startedAt = clock.instant()
                val previous =
                    database(readOnly = true) {
                        query(
                            "SELECT last_incremental_at FROM repository_integrations WHERE id=?",
                            repository.id,
                        ) { it.instant("last_incremental_at") }.single()
                    }
                val cutoff = Instant.ofEpochMilli(requireNotNull(repository.trackingStartedAtEpochMillis))
                val since = if (authoritative || previous == null) cutoff else previous.minusSeconds(1)
                val imported =
                    buildList {
                        if (authoritative ||
                            previous == null
                        ) {
                            addAll(github.repositoryTasks(canonicalRepository, null, initialImport = true))
                        }
                        addAll(github.repositoryTasks(canonicalRepository, since, initialImport = false))
                    }.associateBy { it.nodeId }.values.toList()
                database {
                    lease?.requireOwned(this, clock.instant())
                    apply(
                        this,
                        GitHubProjectionBatch(repositories = listOf(GitHubRepositoryProjection(repository, imported))),
                        clock.instant(),
                    )
                    execute(
                        "UPDATE repository_integrations SET last_incremental_at=? WHERE id=? AND enabled=TRUE AND github_node_id=?",
                        startedAt,
                        repository.id,
                        repository.nodeId,
                    )
                }
            }
            integrations.projects().filter { it.enabled }.forEach { project ->
                val batch = prepareBoard(project)
                database {
                    lease?.requireOwned(this, clock.instant())
                    apply(this, batch, clock.instant())
                }
            }
            val memberships = prepareMemberships()
            database {
                lease?.requireOwned(this, clock.instant())
                apply(this, GitHubProjectionBatch(memberships = memberships), clock.instant())
                if (authoritative) execute("UPDATE github_sync_state SET last_full_completed_at=? WHERE id=1", clock.instant())
                if (recoveryGeneration !=
                    null
                ) {
                    execute(
                        "UPDATE provider_health SET state='HEALTHY',retry_at=NULL,changed_at=? WHERE provider='GITHUB' AND failure_generation=?",
                        clock.instant(),
                        recoveryGeneration,
                    )
                }
            }
        } catch (failure: GitHubApiException) {
            health.markReadFailure(failure)
            throw failure
        } catch (failure: GitHubIdentityClientException) {
            val safe =
                GitHubApiException(
                    status = failure.httpStatus,
                    retryAt = failure.retryAfterSeconds?.let { clock.instant().plusSeconds(it) },
                )
            health.markReadFailure(safe)
            throw safe
        }
    }

    suspend fun prepareWebhook(
        event: String,
        payload: JsonObject,
    ): GitHubProjectionBatch {
        if (event in REPOSITORY_EVENTS) {
            val repositoryNode = payload.requiredObject("repository").requiredText("node_id")
            val repository =
                integrations.repositories().singleOrNull { it.enabled && it.nodeId == repositoryNode } ?: return GitHubProjectionBatch()
            val item = payload.objectOrNull("issue") ?: payload.objectOrNull("pull_request") ?: return GitHubProjectionBatch()
            val number =
                item
                    .requiredLong("number")
                    .also {
                        if (it !in
                            1..Int.MAX_VALUE
                        ) {
                            throw GitHubApiException(invalidContract = true)
                        }
                    }.toInt()
            val snapshot = github.task(repository, number)
            if (snapshot.nodeId != item.requiredText("node_id")) throw GitHubApiException(invalidContract = true)
            return GitHubProjectionBatch(repositories = listOf(GitHubRepositoryProjection(repository, listOf(snapshot))))
        }
        if (event == "projects_v2_item" || event == "projects_v2") {
            val projectNode =
                if (event ==
                    "projects_v2_item"
                ) {
                    payload.requiredObject(event).requiredText("project_node_id")
                } else {
                    payload.requiredObject(event).requiredText("node_id")
                }
            val project = integrations.projects().singleOrNull { it.enabled && it.nodeId == projectNode } ?: return GitHubProjectionBatch()
            return prepareBoard(project)
        }
        if (event == "organization" && payload.text("action") in setOf("member_added", "member_removed")) {
            val githubId =
                payload.objectOrNull("membership")?.objectOrNull("user")?.long("id") ?: throw GitHubApiException(invalidContract = true)
            return GitHubProjectionBatch(memberships = prepareMemberships(githubId))
        }
        return GitHubProjectionBatch() // Signed unknown events are acknowledged without mutations.
    }

    suspend fun prepareBoard(project: ProjectIntegration): GitHubProjectionBatch {
        val requestedAt = clock.instant()
        return try {
            prepareAvailableBoard(project, requestedAt)
        } catch (_: BoardResourceUnavailable) {
            // The caller's lease/receipt transaction owns this change, just like normal projections.
            GitHubProjectionBatch(unavailableBoards = listOf(GitHubUnavailableBoardProjection(project, requestedAt)))
        }
    }

    private suspend fun prepareAvailableBoard(
        project: ProjectIntegration,
        requestedAt: Instant,
    ): GitHubProjectionBatch {
        val snapshot =
            boardRead {
                github.project(project.number).also {
                    if (it.nodeId != project.nodeId) throw GitHubApiException(invalidContract = true)
                    adapter.validateMappings(it, project.fields)
                }
            }
        val repositories = integrations.repositories(project.productId).filter { it.enabled }.associateBy { it.nodeId }
        val repositoryTasks = mutableMapOf<Long, MutableList<GitHubTaskSnapshot>>()
        val drafts = mutableListOf<Pair<GitHubTaskSnapshot, TaskProjectContext>>()
        val contexts = linkedMapOf<String, TaskProjectContext>()
        boardRead { github.projectItems(snapshot.nodeId) }.forEach { item ->
            val content = item.objectOrNull("content") ?: return@forEach
            val context = boardRead { adapter.context(project, item) }
            val contentId = boardRead { content.requiredText("id") }
            when (boardRead { content.requiredText("__typename") }) {
                "Issue", "PullRequest" -> {
                    val repositoryNode = boardRead { content.requiredObject("repository").requiredText("id") }
                    val repository = repositories[repositoryNode] ?: return@forEach
                    val number =
                        boardRead {
                            content
                                .requiredLong("number")
                                .also {
                                    if (it !in 1..Int.MAX_VALUE) throw GitHubApiException(invalidContract = true)
                                }.toInt()
                        }
                    // Repository fetch failures must still trigger provider recovery, not disable a board.
                    val snapshotTask = github.task(repository, number)
                    if (snapshotTask.nodeId != contentId) throw BoardResourceUnavailable()
                    repositoryTasks.getOrPut(repository.id) { mutableListOf() }.add(snapshotTask)
                }

                "DraftIssue" -> {
                    drafts += boardRead {
                        GitHubTaskSnapshot(
                            nodeId = contentId,
                            kind = TaskKind.DRAFT,
                            number = null,
                            title = content.requiredText("title"),
                            body = content.text("body").orEmpty(),
                            url = snapshot.url ?: throw GitHubApiException(invalidContract = true),
                            open = !context.archived && context.status != TaskStatus.DONE,
                            completed = context.status == TaskStatus.DONE,
                            createdAt = content.requiredInstant("createdAt"),
                            updatedAt = maxOf(content.requiredInstant("updatedAt"), item.requiredInstant("updatedAt")),
                            closedAt = null,
                            assignees = content.objects("allAssignees").map(JsonObject::graphActor),
                            reviewAssignments = emptyList(),
                            completions = emptyList(),
                            details =
                                buildJsonObject {
                                },
                            snapshotRequestedAt = requestedAt,
                        )
                    } to context
                }

                else -> {
                    throw BoardResourceUnavailable()
                }
            }
            if (contexts.put(contentId, context) != null) throw BoardResourceUnavailable()
        }
        return GitHubProjectionBatch(
            repositoryTasks.map { (id, snapshots) -> GitHubRepositoryProjection(repositories.values.single { it.id == id }, snapshots) },
            listOf(GitHubBoardProjection(project, snapshot, requestedAt, drafts, contexts)),
        )
    }

    private suspend fun <T> boardRead(operation: suspend () -> T): T =
        try {
            operation()
        } catch (failure: GitHubApiException) {
            if (failure.resourceUnavailable || (failure.invalidContract && !failure.authenticationFailure)) {
                throw BoardResourceUnavailable()
            }
            throw failure
        }

    private suspend fun prepareMemberships(onlyGitHubId: Long? = null): List<GitHubMembershipProjection> {
        val candidates =
            database(readOnly = true) {
                query(
                    "SELECT u.id,u.profile_version,g.github_user_id FROM users u JOIN user_github_links g ON g.user_id=u.id WHERE u.is_active=TRUE AND g.github_user_id IS NOT NULL AND (? IS NULL OR g.github_user_id=?)",
                    onlyGitHubId,
                    onlyGitHubId,
                ) {
                    Triple(it.getLong("id"), it.getLong("profile_version"), it.getLong("github_user_id"))
                }
            }
        return candidates.map { (userId, version, githubId) ->
            val startedAt = clock.instant()
            val user = github.userById(githubId)
            val outcome =
                if (user == null) {
                    ProjectedGitHubOutcome.AccountNotFound
                } else {
                    val link = GitHubLinkApproval(user.numericId, user.login)
                    if (identity.hasActiveOrganizationMembership(
                            user,
                        )
                    ) {
                        ProjectedGitHubOutcome.Verified(link)
                    } else {
                        ProjectedGitHubOutcome.NotOrganizationMember(link)
                    }
                }
            GitHubMembershipProjection(userId, version, githubId, startedAt, outcome)
        }
    }

    /** Receipt/lease must be locked before this call; the caller commits its receipt in this transaction. */
    fun apply(
        connection: Connection,
        batch: GitHubProjectionBatch,
        now: Instant,
    ) = with(connection) {
        check(!autoCommit)
        lockMemberManagement()
        batch.unavailableBoards.forEach { board ->
            integrations.setProjectUnavailable(this, board.project, board.requestedAt, now)
        }
        batch.repositories.forEach { projection ->
            val repository = projection.repository
            val current =
                query(
                    "SELECT github_node_id FROM repository_integrations WHERE id=? AND enabled=TRUE FOR UPDATE",
                    repository.id,
                ) {
                    it.getString(1)
                }.singleOrNull()
            if (current == repository.nodeId) projection.tasks.forEach { tasks.ingestRepository(this, repository.id, it, now) }
        }
        batch.boards.forEach { board ->
            val current =
                query(
                    "SELECT github_node_id,updated_at FROM project_integrations WHERE id=? AND enabled=TRUE FOR UPDATE",
                    board.project.id,
                ) {
                    it.getString(1) to
                        requireNotNull(it.instant("updated_at"))
                }.singleOrNull()
            if (current == null || current.first != board.project.nodeId || current.second.isAfter(board.requestedAt)) return@forEach
            val fields =
                query("SELECT semantic,field_node_id,option_ids_json FROM project_field_mappings WHERE project_id=?", board.project.id) {
                    Triple(
                        ProjectSemanticField.valueOf(it.getString(1)),
                        it.getString(2),
                        Json.decodeFromString<Map<String, String>>(it.getString(3)),
                    )
                }.toSet()
            if (fields !=
                board.project.fields
                    .map { Triple(it.semantic, it.fieldNodeId, it.options) }
                    .toSet()
            ) {
                return@forEach
            }
            board.drafts.forEach { (snapshot, context) -> tasks.ingestDraft(this, board.project.id, snapshot, context, now) }
            val contexts =
                board.contexts
                    .mapNotNull { (node, context) ->
                        query("SELECT id FROM task_projections WHERE canonical_node_id=?", node) { it.getLong(1) }.singleOrNull()?.let {
                            it to
                                context
                        }
                    }.toMap()
            tasks.replaceBoardSnapshot(this, board.project.id, contexts)
            execute(
                "UPDATE project_integrations SET title=?,repository_link_warning=?,updated_at=? WHERE id=?",
                board.snapshot.title,
                board.snapshot.repositoryNodeIds.isEmpty(),
                now,
                board.project.id,
            )
        }
        batch.memberships.forEach { member ->
            val current =
                query(
                    "SELECT 1 FROM users u JOIN user_github_links g ON g.user_id=u.id WHERE u.id=? AND u.is_active=TRUE AND u.profile_version=? AND g.github_user_id=? AND (g.membership_checked_at IS NULL OR g.membership_checked_at<=?) FOR UPDATE",
                    member.userId,
                    member.profileVersion,
                    member.githubId,
                    member.requestedAt,
                ) {
                    it.getInt(1)
                }.isNotEmpty()
            if (current) authStore.completeProjectedGitHubVerification(this, member.userId, member.profileVersion, member.outcome, now)
        }
    }

    private suspend fun <T> database(
        readOnly: Boolean = false,
        block: Connection.() -> T,
    ): T =
        withContext(Dispatchers.IO) {
            dataSource.inTransaction(readOnly = readOnly, block = block)
        }

    private companion object {
        val REPOSITORY_EVENTS = setOf("issues", "issue_comment", "pull_request", "pull_request_review", "pull_request_review_comment")
    }
}

private class BoardResourceUnavailable : RuntimeException("Project board requires integration repair")
