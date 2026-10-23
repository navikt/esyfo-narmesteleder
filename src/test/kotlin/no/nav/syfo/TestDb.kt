package no.nav.syfo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import no.nav.syfo.platform.database.DatabaseInterface
import no.nav.syfo.sykmelding.infrastructure.ExposedSykmeldingDb
import no.nav.syfo.sykmelding.infrastructure.SendtSykmeldingTable
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.use
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

class PsqlContainer : PostgreSQLContainer<PsqlContainer>("postgres:18-alpine")

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
        val exposedDatabase: ExposedDatabase by lazy {
            ExposedDatabase.connect(
                url = psqlContainer.jdbcUrl,
                user = "username",
                password = "password",
                driver = "org.postgresql.Driver",
                setupConnection = { connection ->
                    connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                },
            )
        }

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

        /** Truncates every application table created by Flyway, leaving the migration history intact. */
        fun clearAllData() = database.connection.use { connection ->
            val tables = connection.prepareStatement(
                """
                SELECT quote_ident(table_name) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            check(tables.isNotEmpty()) { "Found no application tables to truncate" }
            connection.prepareStatement("TRUNCATE TABLE ${tables.joinToString()} CASCADE").use { it.executeUpdate() }
            connection.commit()
        }
    }
}

data class PersistedSendtSykmelding(
    val id: Long,
    val sykmeldingId: UUID,
    val fnr: String,
    val orgnummer: String,
    val fom: LocalDate,
    val tom: LocalDate,
    val syketilfelleStartDato: LocalDate?,
    val revokedDate: LocalDate?,
    val created: Instant,
    val updated: Instant,
)

fun ExposedSykmeldingDb.findAll(): List<PersistedSendtSykmelding> = transaction(TestDB.exposedDatabase) {
    SendtSykmeldingTable.selectAll().map { it.toPersistedSendtSykmelding() }
}

fun ExposedSykmeldingDb.findBySykmeldingId(sykmeldingId: UUID): PersistedSendtSykmelding? = transaction(TestDB.exposedDatabase) {
    SendtSykmeldingTable
        .selectAll()
        .where { SendtSykmeldingTable.sykmeldingId eq sykmeldingId }
        .singleOrNull()
        ?.toPersistedSendtSykmelding()
}

private fun ResultRow.toPersistedSendtSykmelding() = PersistedSendtSykmelding(
    id = this[SendtSykmeldingTable.id].value.toLong(),
    sykmeldingId = this[SendtSykmeldingTable.sykmeldingId],
    fnr = this[SendtSykmeldingTable.fnr],
    orgnummer = this[SendtSykmeldingTable.orgnummer],
    fom = this[SendtSykmeldingTable.fom],
    tom = this[SendtSykmeldingTable.tom],
    syketilfelleStartDato = this[SendtSykmeldingTable.syketilfelleStartDato],
    revokedDate = this[SendtSykmeldingTable.revokedDate],
    created = this[SendtSykmeldingTable.created].toInstant(),
    updated = this[SendtSykmeldingTable.updated].toInstant(),
)
