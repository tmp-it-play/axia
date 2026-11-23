package team.themoment.axia.simulation.military

import team.themoment.axia.simulation.world.Geography

/** partner가 없으면 대상국의 모든 모형 해상 교역, 있으면 두 나라 사이의 양방향 선적에 적용한다. */
data class MaritimeRestriction(
    val issuer: String,
    val target: String,
    val partner: String?,
) {
    companion object {
        const val ROUTE_COST = 5.0
        const val BLOCKADED_CAPACITY = 0.45
    }
}

internal class MaritimeTrade {
    private val orders = linkedSetOf<MaritimeRestriction>()
    private val blockedCountries = hashSetOf<String>()
    private val closedRoutes = hashMapOf<String, MutableSet<String>>()

    fun snapshot() = orders.toList()

    fun has(order: MaritimeRestriction) = order in orders

    fun set(
        order: MaritimeRestriction,
        impose: Boolean,
    ) {
        if (impose) orders.add(order) else orders.remove(order)
        rebuild()
    }

    fun clearInactive(atWar: (String, String) -> Boolean) {
        if (orders.removeIf { !atWar(it.issuer, it.target) }) rebuild()
    }

    private fun rebuild() {
        blockedCountries.clear()
        closedRoutes.clear()
        orders.forEach { (_, target, partner) ->
            if (partner == null) {
                blockedCountries.add(target)
            } else {
                closedRoutes.getOrPut(target, ::hashSetOf).add(partner)
                closedRoutes.getOrPut(partner, ::hashSetOf).add(target)
            }
        }
    }

    fun availability(
        a: String,
        b: String,
    ): Double {
        if (orders.isEmpty()) return 1.0
        if (closedRoutes[a]?.contains(b) == true) return 0.0
        if (a !in blockedCountries && b !in blockedCountries) return 1.0
        return if (seaRoute(a, b)) MaritimeRestriction.BLOCKADED_CAPACITY else 1.0
    }

    companion object {
        /** 복합 운송 자료가 없어 서로 국경을 접하지 않는 연안국 간 교역을 해상으로 분류한다. */
        fun seaRoute(
            a: String,
            b: String,
        ): Boolean {
            val geography = Geography.default
            return a != b && geography.of(a).coastal && geography.of(b).coastal && !geography.neighbors(a, b)
        }
    }
}
