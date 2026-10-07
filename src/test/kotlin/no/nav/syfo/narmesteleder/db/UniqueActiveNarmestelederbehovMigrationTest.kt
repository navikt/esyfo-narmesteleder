package no.nav.syfo.narmesteleder.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import nlBehovEntity
import no.nav.syfo.PsqlContainer
import no.nav.syfo.application.database.DatabaseInterface
import no.nav.syfo.narmesteleder.domain.BehovStatus
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.sql.Connection
import java.sql.DriverManager

class UniqueActiveNarmestelederbehovMigrationTest :
    DescribeSpec({
        lateinit var container: PsqlContainer
        lateinit var database: DatabaseInterface

        fun flyway(target: String = "latest"): Flyway = Flyway.configure()
            .locations("db")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .dataSource(container.jdbcUrl, container.username, container.password)
            .target(target)
            .load()

        fun indexIsValid(): Boolean = database.connection.use { connection ->
            connection.prepareStatement(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = 'uq_nl_behov_active_employee_org'::regclass",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    result.next() shouldBe true
                    result.getBoolean("indisvalid")
                }
            }
        }

        beforeTest {
            container = PsqlContainer()
                .withUsername("username")
                .withPassword("password")
                .withDatabaseName("database")
            container.start()
            database = object : DatabaseInterface {
                override val connection: Connection
                    get() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password).apply {
                        autoCommit = false
                    }
            }
        }

        afterTest {
            container.stop()
        }

        describe("V30 concurrent unique index migration on PostgreSQL 18") {
            it("migrates an empty database to a valid index") {
                flyway().migrate()

                flyway().info().current().version.toString() shouldBe "30"
                indexIsValid() shouldBe true
            }

            it("upgrades V28 with history and no active duplicates without changing rows") {
                flyway("28").migrate()
                val db = PostgresNarmestelederDb(database)
                val entity = nlBehovEntity()
                val active = db.insertNlBehov(entity)
                val attention = db.insertNlBehov(nlBehovEntity().copy(behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION))
                val historical = db.insertNlBehov(entity.copy(behovStatus = BehovStatus.BEHOV_FULFILLED))
                db.insertNlBehov(entity.copy(behovStatus = BehovStatus.BEHOV_FULFILLED))

                flyway().migrate()

                flyway().info().current().version.toString() shouldBe "30"
                indexIsValid() shouldBe true
                db.findBehovById(requireNotNull(active.id))?.behovStatus shouldBe BehovStatus.BEHOV_CREATED
                db.findBehovById(requireNotNull(attention.id))?.behovStatus shouldBe BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                db.findBehovById(requireNotNull(historical.id))?.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
                db.findBehovByParameters(entity.sykmeldtFnr, entity.orgnummer, listOf(BehovStatus.BEHOV_FULFILLED)).size shouldBe 2
            }

            it("fails upgrading V28 with active duplicates and leaves an invalid index and failed history entry") {
                flyway("28").migrate()
                val db = PostgresNarmestelederDb(database)
                val entity = nlBehovEntity()
                db.insertNlBehov(entity)
                db.insertNlBehov(entity.copy(behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION))

                shouldThrow<FlywayException> { flyway().migrate() }

                indexIsValid() shouldBe false
                database.connection.use { connection ->
                    connection.prepareStatement("SELECT success FROM flyway_schema_history WHERE version = '30'").use { statement ->
                        statement.executeQuery().use { result ->
                            result.next() shouldBe true
                            result.getBoolean("success") shouldBe false
                            result.next() shouldBe false
                        }
                    }
                }
                db.findBehovByParameters(
                    entity.sykmeldtFnr,
                    entity.orgnummer,
                    listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
                ).size shouldBe 2
            }
        }
    })
