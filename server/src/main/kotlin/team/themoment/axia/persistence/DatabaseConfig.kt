package team.themoment.axia.persistence

class DatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val poolName: String = "axia-database",
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = minOf(2, maximumPoolSize),
    val connectionTimeoutMillis: Long = 10_000,
    val validationTimeoutMillis: Long = 5_000,
    val idleTimeoutMillis: Long = 600_000,
    val maxLifetimeMillis: Long = 1_800_000,
) {
    init {
        require(jdbcUrl.isNotBlank()) { "Database JDBC URL must not be blank" }
        require(username.isNotBlank()) { "Database username must not be blank" }
        require(poolName.isNotBlank()) { "Database pool name must not be blank" }
        require(maximumPoolSize > 0) { "Maximum pool size must be positive" }
        require(minimumIdle in 0..maximumPoolSize) {
            "Minimum idle connections must be between zero and the maximum pool size"
        }
        require(connectionTimeoutMillis >= 250) { "Connection timeout must be at least 250 ms" }
        require(validationTimeoutMillis >= 250) { "Validation timeout must be at least 250 ms" }
        require(validationTimeoutMillis < connectionTimeoutMillis) {
            "Validation timeout must be shorter than the connection timeout"
        }
        require(idleTimeoutMillis == 0L || idleTimeoutMillis >= 10_000) {
            "Idle timeout must be zero or at least 10 seconds"
        }
        require(maxLifetimeMillis == 0L || maxLifetimeMillis >= 30_000) {
            "Maximum connection lifetime must be zero or at least 30 seconds"
        }
    }
}
