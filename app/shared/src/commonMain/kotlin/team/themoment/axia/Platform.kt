package team.themoment.axia

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform