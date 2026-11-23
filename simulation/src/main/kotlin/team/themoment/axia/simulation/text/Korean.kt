package team.themoment.axia.simulation.text

/** 마지막 글자의 받침에 맞춰 조사를 붙인다. 한글이 아니면 "이(가)"처럼 둘 다 쓴다. "으로/로"는 ㄹ 받침이면 "로"다. */
fun String.josa(
    withFinal: String,
    withoutFinal: String,
): String {
    // "혁명노조연맹(DİSK)"처럼 괄호로 끝나면 괄호 앞 글자를 본다.
    val core = if (endsWith(")") && contains("(")) substringBeforeLast("(").trimEnd() else this
    val last = core.lastOrNull() ?: return this
    // 숫자는 한국어 읽기(영, 일, 이, ...)의 받침을 본다.
    val finalConsonant =
        when (last) {
            in '0'..'9' -> DIGIT_FINALS[last - '0']
            in '가'..'힣' -> (last - '가') % 28
            else -> return "$this$withFinal($withoutFinal)"
        }
    val useFinal = finalConsonant != 0 && !(withFinal == "으로" && finalConsonant == RIEUL)
    return this + if (useFinal) withFinal else withoutFinal
}

private const val RIEUL = 8

/** 0~9를 읽을 때 마지막 글자의 받침 번호(영·일·이·삼·사·오·육·칠·팔·구). */
private val DIGIT_FINALS = intArrayOf(21, RIEUL, 0, 16, 0, 0, 1, RIEUL, RIEUL, 0)
