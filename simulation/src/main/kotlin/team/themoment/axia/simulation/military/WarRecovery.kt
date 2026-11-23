package team.themoment.axia.simulation.military

import team.themoment.axia.simulation.world.Geography

/** 게임 시작 이후 발생한 국경 간 피란만 추적한다. 인원은 월간 모형의 기댓값이다. */
data class RefugeeRoute(
    val origin: String,
    val host: String,
    val people: Double,
)

data class WarRecoverySnapshot(
    val refugees: List<RefugeeRoute> = emptyList(),
    val returned: Map<String, Double> = emptyMap(),
    /** 남은 복구 수요와 당월 집행액(연율)은 양쪽 모두 연 GDP 대비 비율이다. */
    val damage: Map<String, Double> = emptyMap(),
    val reconstructionShares: Map<String, Double> = emptyMap(),
)

internal data class RecoveryMonth(
    val netMigration: Map<String, Double>,
    val reconstructionShares: Map<String, Double>,
)

enum class ReconstructionEffort(
    val label: String,
    val annualShare: Double,
    val returnRate: Double,
) {
    BASIC("최소 복구", 0.0025, 0.04),
    STANDARD("표준 복구", 0.01, 0.08),
    ACCELERATED("집중 복구", 0.02, 0.12),
}

internal class WarRecovery(
    private val playerId: String,
) {
    var effort = ReconstructionEffort.STANDARD

    private fun policy(id: String) = if (id == playerId) effort else ReconstructionEffort.STANDARD

    private val geography = Geography.default
    private val displaced = mutableMapOf<Pair<String, String>, Double>()
    private val damage = mutableMapOf<String, Double>()
    var snapshot = WarRecoverySnapshot()
        private set

    fun advance(
        population: Map<String, Double>,
        intensity: Map<String, Double>,
        hostile: (String, String) -> Boolean,
    ): RecoveryMonth {
        val net = mutableMapOf<String, Double>()
        val returned = mutableMapOf<String, Double>()
        val spending = mutableMapOf<String, Double>()

        fun move(
            from: String,
            to: String,
            people: Double,
        ) {
            net[from] = net.getOrDefault(from, 0.0) - people
            net[to] = net.getOrDefault(to, 0.0) + people
        }

        // 먼저 귀환을 처리해 이번 달에 떠난 사람이 같은 달에 돌아오는 것을 막는다.
        val iterator = displaced.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val (origin, host) = entry.key
            if (intensity.getValue(origin) > 0.0) continue
            val stock = entry.value
            val available = (population.getValue(host) + net.getOrDefault(host, 0.0)).coerceAtLeast(0.0)
            val count = minOf(stock, maxOf(stock * policy(origin).returnRate, 1.0), available * 0.02)
            move(host, origin, count)
            returned[origin] = returned.getOrDefault(origin, 0.0) + count
            if (count == stock) iterator.remove() else entry.setValue(stock - count)
        }

        population.keys.sorted().forEach { origin ->
            val danger = intensity.getValue(origin)
            val backlog = damage.getOrDefault(origin, 0.0)
            if (danger <= 0.0) {
                // 매달 예산 한도와 남은 피해 중 작은 쪽을 집행한다.
                if (backlog > 0.0) {
                    val spent = minOf(backlog, policy(origin).annualShare / 12.0)
                    spending[origin] = spent * 12.0
                    if (spent == backlog) damage.remove(origin) else damage[origin] = backlog - spent
                }
                return@forEach
            }
            damage[origin] = (backlog + 0.003 * danger).coerceAtMost(0.30)
            val alreadyAbroad = displaced.entries.sumOf { (route, people) -> if (route.first == origin) people else 0.0 }
            val residents = population.getValue(origin)
            val leaving = minOf(residents * danger * 0.0007, (0.12 * (residents + alreadyAbroad) - alreadyAbroad).coerceAtLeast(0.0))
            if (leaving == 0.0) return@forEach

            fun safe(host: String): Boolean = host != origin && intensity[host] == 0.0 && !hostile(origin, host)
            val neighbors =
                geography
                    .of(origin)
                    .neighbors
                    .filter(::safe)
                    .sorted()
            val hosts =
                neighbors.ifEmpty {
                    population.keys
                        .asSequence()
                        .filter(::safe)
                        .sortedBy { geography.distanceKm(origin, it) }
                        .take(3)
                        .toList()
                }
            val capacity = hosts.sumOf(population::getValue)
            if (capacity <= 0.0) return@forEach
            hosts.forEach { host ->
                val count = leaving * population.getValue(host) / capacity
                move(origin, host, count)
                val route = origin to host
                displaced[route] = displaced.getOrDefault(route, 0.0) + count
            }
        }
        snapshot =
            WarRecoverySnapshot(
                displaced.map { (route, count) -> RefugeeRoute(route.first, route.second, count) },
                returned,
                damage.toMap(),
                spending,
            )
        return RecoveryMonth(net, spending)
    }
}
