package team.themoment.axia.update

/** 릴리즈 태그(`v1.2.3`)와 설치 패키지가 공유하는 `MAJOR.MINOR.PATCH` 버전. */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

        fun parse(text: String): AppVersion? {
            val (major, minor, patch) = PATTERN.matchEntire(text)?.destructured ?: return null
            return AppVersion(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
            )
        }
    }
}
