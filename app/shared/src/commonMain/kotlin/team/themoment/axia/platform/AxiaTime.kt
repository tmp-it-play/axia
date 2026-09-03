package team.themoment.axia.platform

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

object AxiaTime {
    val zone: TimeZone = TimeZone.of("Asia/Seoul")

    fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

    fun today(): LocalDate =
        Clock.System
            .now()
            .toLocalDateTime(zone)
            .date

    fun localDateTime(epochMillis: Long): LocalDateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)

    fun label(epochMillis: Long): String {
        val local = localDateTime(epochMillis)
        return "${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    }

    fun parseInput(value: String): Long? =
        runCatching {
            LocalDateTime.parse(value.trim().replace(' ', 'T')).toInstant(zone).toEpochMilliseconds()
        }.getOrNull()
}
