package no.nav.syfo.platform.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import org.flywaydb.core.Flyway
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
import java.sql.Connection

data class DatabaseConfig(
    val jdbcUrl: String,
    val password: String,
    val username: String,
    val poolSize: Int = 4,
)

class Database(
    private val config: DatabaseConfig
) : DatabaseInterface {
    override val connection: Connection
        get() = dataSource.connection

    val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.username
            password = config.password
            maximumPoolSize = config.poolSize
            minimumIdle = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            metricRegistry = METRICS_REGISTRY
            validate()
        }
    )

    init {
        runFlywayMigrations()
    }

    private fun runFlywayMigrations() = Flyway.configure().run {
        getConfigurationExtension(PostgreSQLConfigurationExtension::class.java).isTransactionalLock = false
        dataSource(
            config.jdbcUrl,
            config.username,
            config.password,
        )

        val flyway = load()
        flyway.migrate().migrationsExecuted
    }
}

interface DatabaseInterface {
    val connection: Connection
}
