package no.nav.syfo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import no.nav.syfo.application.database.DatabaseInterface
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.time.ExperimentalTime
import kotlin.use

class PsqlContainer : PostgreSQLContainer<PsqlContainer>("postgres:17-alpine")

private val log = LoggerFactory.getLogger(TestDB::class.qualifiedName)

class TestDatabase(
    private val connectionName: String,
    private val dbUsername: String,
    private val dbPassword: String,
) : DatabaseInterface {
    private val dataSource: HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = connectionName
                username = dbUsername
                password = dbPassword
                maximumPoolSize = 1
                minimumIdle = 1
                isAutoCommit = false
                connectionTimeout = 10_000
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            },
        )
    override val connection: Connection
        get() = dataSource.connection

    init {
        runFlywayMigrations()
    }

    private fun runFlywayMigrations() = Flyway.configure().run {
        locations("db")
        configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
        dataSource(connectionName, dbUsername, dbPassword)
        load().migrate()
    }
}

class TestDB private constructor() {
    companion object {
        val database: DatabaseInterface

        private val psqlContainer: PsqlContainer

        init {
            try {
                psqlContainer =
                    PsqlContainer()
                        .withExposedPorts(5432)
                        .withUsername("username")
                        .withPassword("password")
                        .withDatabaseName("database")

                psqlContainer.waitingFor(HostPortWaitStrategy())
                psqlContainer.start()
                val username = "username"
                val password = "password"
                val connectionName = psqlContainer.jdbcUrl

                database = TestDatabase(connectionName, username, password)
            } catch (ex: Exception) {
                log.error("Error", ex)
                throw ex
            }
        }

        @OptIn(ExperimentalTime::class)
        fun updateCreated(id: UUID, timestamp: Instant) {
            database.connection.use {
                it.prepareStatement(
                    "UPDATE nl_behov SET created = ? WHERE id = ?;"
                ).use { preparedStatement ->
                    preparedStatement.setTimestamp(1, Timestamp.from(timestamp))
                    preparedStatement.setObject(2, id)
                    preparedStatement.executeUpdate()
                }
                it.commit()
            }
        }

        fun clearAllData() = database.connection.use {
            it.prepareStatement(
                "DELETE FROM nl_behov;"
            ).use { ps -> ps.executeUpdate() }
            it.commit()
        }

        fun clearSendtSykmeldingData() = database.connection.use {
            it.prepareStatement(
                "DELETE FROM sendt_sykmelding;"
            ).use { ps -> ps.executeUpdate() }
            it.commit()
        }
    }
}
