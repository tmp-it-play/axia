package team.themoment.axia.format

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

private const val MAN = 1e4
private const val EOK = 1e8
private const val JO = 1e12

private fun Long.grouped(): String = String.format(Locale.KOREA, "%,d", this)

/** 예: 1조 8,439억 달러, 300억 달러, 25만 달러 */
fun formatDollars(value: Double): String =
    when {
        value >= JO -> {
            var jo = floor(value / JO).toLong()
            var eok = ((value - jo * JO) / EOK).roundToLong()
            if (eok >= 10_000) {
                jo += 1
                eok -= 10_000
            }
            if (eok == 0L) "${jo.grouped()}조 달러" else "${jo.grouped()}조 ${eok.grouped()}억 달러"
        }

        value >= EOK -> {
            "${(value / EOK).roundToLong().grouped()}억 달러"
        }

        value >= MAN -> {
            "${(value / MAN).roundToLong().grouped()}만 달러"
        }

        else -> {
            "${value.roundToLong().grouped()}달러"
        }
    }

/** 예: 14억 967만 명, 5,171만 명, 800명 */
fun formatPopulation(value: Double): String =
    when {
        value >= EOK -> {
            val eok = floor(value / EOK).toLong()
            val man = ((value - eok * EOK) / MAN).roundToLong()
            if (man == 0L) "${eok}억 명" else "${eok}억 ${man.grouped()}만 명"
        }

        value >= MAN -> {
            "${(value / MAN).roundToLong().grouped()}만 명"
        }

        else -> {
            "${value.roundToLong().grouped()}명"
        }
    }

fun formatWholeDollars(value: Double): String = "${value.roundToLong().grouped()}달러"

/** 금리처럼 0.25%p 단위로 움직이는 값. 소수 둘째 자리까지 쓴다. */
fun formatRate(
    ratio: Double,
    signed: Boolean = false,
): String = String.format(Locale.KOREA, if (signed) "%+.2f%%" else "%.2f%%", ratio * 100.0)

/** 비율(0.031)을 백분율 문자열(3.1%)로. [signed]면 양수에 +를 붙인다. */
fun formatPercent(
    ratio: Double,
    signed: Boolean = false,
): String {
    val percent = ratio * 100.0
    val digits = if (abs(percent) >= 100.0) 0 else 1
    val pattern = if (signed) "%+.${digits}f%%" else "%.${digits}f%%"
    return String.format(Locale.KOREA, pattern, percent)
}

/** 차트 눈금처럼 좁은 자리에 쓰는 짧은 금액. 예: 1.8조 달러, 304억 달러 */
fun formatCompactDollars(value: Double): String =
    when {
        abs(value) >= JO -> String.format(Locale.KOREA, "%.1f조 달러", value / JO)
        abs(value) >= EOK -> String.format(Locale.KOREA, "%,.0f억 달러", value / EOK)
        else -> String.format(Locale.KOREA, "%,.0f달러", value)
    }
