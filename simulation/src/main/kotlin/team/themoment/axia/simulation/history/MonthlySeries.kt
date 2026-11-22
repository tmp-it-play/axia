package team.themoment.axia.simulation.history

/** 월간 값을 최대 [capacity]개까지 보관한다. 넘치면 가장 오래된 값부터 버린다. */
internal class MonthlySeries(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val values = DoubleArray(capacity)
    private var start = 0
    private var size = 0

    /** 지금까지 버린 값의 개수. 첫 값의 월을 계산하는 데 쓴다. */
    var dropped = 0
        private set

    fun record(value: Double) {
        if (size < capacity) {
            values[(start + size) % capacity] = value
            size += 1
        } else {
            values[start] = value
            start = (start + 1) % capacity
            dropped += 1
        }
    }

    fun toList(): List<Double> = List(size) { values[(start + it) % capacity] }

    companion object {
        /** 50년. */
        const val DEFAULT_CAPACITY = 600
    }
}
