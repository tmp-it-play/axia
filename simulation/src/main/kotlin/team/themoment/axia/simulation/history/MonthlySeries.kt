package team.themoment.axia.simulation.history

/** 시작부터 월간 값을 보관한다. [limit]을 지정한 기록은 최근 값만 남긴다. */
internal class MonthlySeries(
    private val limit: Int? = null,
) {
    private var values = DoubleArray(limit ?: 16)
    private var start = 0
    private var size = 0
    private var snapshot: List<Double>? = null

    /** 지금까지 버린 값의 개수. 첫 값의 월을 계산하는 데 쓴다. */
    var dropped = 0
        private set

    fun record(value: Double) {
        snapshot = null
        if (limit == null && size == values.size) values = values.copyOf(size * 2)
        if (size < values.size) {
            values[size++] = value
        } else {
            values[start] = value
            start = (start + 1) % values.size
            dropped += 1
        }
    }

    fun toList(): List<Double> {
        snapshot?.let { return it }
        // 전체 기록은 뒤에만 추가하므로 기존 배열의 고정 구간을 여러 스냅숏이 안전하게 공유한다.
        val recorded =
            if (limit == null) {
                values.asList().subList(0, size)
            } else {
                DoubleArray(size) { values[(start + it) % values.size] }.asList()
            }
        snapshot = recorded
        return recorded
    }
}
