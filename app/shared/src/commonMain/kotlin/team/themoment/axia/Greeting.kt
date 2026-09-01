package team.themoment.axia

class Greeting {
    private val platform = getPlatform()

    fun greet(): String = sayHello(platform.name)
}
