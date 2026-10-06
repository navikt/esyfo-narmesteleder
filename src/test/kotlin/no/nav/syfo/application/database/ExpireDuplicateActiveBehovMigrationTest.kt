package no.nav.syfo.application.database

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.PsqlContainer
import org.flywaydb.core.Flyway
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy
import java.sql.DriverManager
import java.util.UUID

class ExpireDuplicateActiveBehovMigrationTest :
    DescribeSpec({
        val container = PsqlContainer()
            .withUsername("username")
            .withPassword("password")
            .withDatabaseName("database")
            .waitingFor(HostPortWaitStrategy())

        beforeSpec { container.start() }
        afterSpec { container.stop() }

        fun flyway(target: String) = Flyway.configure()
            .locations("db")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .dataSource(container.jdbcUrl, container.username, container.password)
            .cleanDisabled(false)
            .target(target)
            .load()

        beforeTest {
            flyway("28").run {
                clean()
                migrate()
            }
        }

        fun insert(id: UUID, status: String, fnr: String, orgnummer: String = "999888777") = DriverManager
            .getConnection(container.jdbcUrl, container.username, container.password)
            .use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO nl_behov (id, orgnummer, sykemeldt_fnr, behov_status, dialog_id)
                    VALUES (?, ?, ?, ?::behov_status, gen_random_uuid())
                    """.trimIndent()
                ).use {
                    it.setObject(1, id)
                    it.setString(2, orgnummer)
                    it.setString(3, fnr)
                    it.setString(4, status)
                    it.executeUpdate()
                }
            }

        fun statusOf(id: UUID): String = DriverManager
            .getConnection(container.jdbcUrl, container.username, container.password)
            .use { connection ->
                connection.prepareStatement("SELECT behov_status FROM nl_behov WHERE id = ?").use {
                    it.setObject(1, id)
                    it.executeQuery().run {
                        next()
                        getString(1)
                    }
                }
            }

        val fulfilledAnchor = UUID.fromString("16cac3ed-b483-4a26-b329-a809c310c0bd")
        val expiredCreated = UUID.fromString("0438ed71-7326-4cf8-a414-21c66b0be39e")
        val expiredAttention = UUID.fromString("48d0956a-558b-479e-86a4-87c84b0fda19")

        describe("V29 expire duplicate active narmestelederbehov") {
            it("fulfils the pair with a registered leader and expires the newest row in the other pairs") {
                val anchorPartner = UUID.randomUUID()
                val anchorOtherOrg = UUID.randomUUID()
                val keeperCreated = UUID.randomUUID()
                val keeperAttention = UUID.randomUUID()
                insert(anchorPartner, "DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION", "11111111111")
                insert(fulfilledAnchor, "BEHOV_CREATED", "11111111111")
                insert(anchorOtherOrg, "BEHOV_CREATED", "11111111111", orgnummer = "111222333")
                insert(keeperCreated, "BEHOV_CREATED", "22222222222")
                insert(expiredCreated, "BEHOV_CREATED", "22222222222")
                insert(keeperAttention, "DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION", "33333333333")
                insert(expiredAttention, "DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION", "33333333333")

                flyway("29").migrate().migrationsExecuted shouldBe 1

                statusOf(fulfilledAnchor) shouldBe "BEHOV_FULFILLED"
                statusOf(anchorPartner) shouldBe "BEHOV_FULFILLED"
                statusOf(anchorOtherOrg) shouldBe "BEHOV_CREATED"
                statusOf(expiredCreated) shouldBe "BEHOV_EXPIRED"
                statusOf(expiredAttention) shouldBe "BEHOV_EXPIRED"
                statusOf(keeperCreated) shouldBe "BEHOV_CREATED"
                statusOf(keeperAttention) shouldBe "DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION"
            }

            it("leaves rows unchanged when the listed behov are no longer active") {
                val newActiveInAnchorPair = UUID.randomUUID()
                insert(fulfilledAnchor, "DIALOGPORTEN_STATUS_SET_COMPLETED", "11111111111")
                insert(newActiveInAnchorPair, "BEHOV_CREATED", "11111111111")
                insert(expiredCreated, "BEHOV_FULFILLED", "22222222222")
                insert(expiredAttention, "DIALOGPORTEN_STATUS_SET_COMPLETED", "33333333333")

                flyway("29").migrate().migrationsExecuted shouldBe 1

                statusOf(fulfilledAnchor) shouldBe "DIALOGPORTEN_STATUS_SET_COMPLETED"
                statusOf(newActiveInAnchorPair) shouldBe "BEHOV_CREATED"
                statusOf(expiredCreated) shouldBe "BEHOV_FULFILLED"
                statusOf(expiredAttention) shouldBe "DIALOGPORTEN_STATUS_SET_COMPLETED"
            }
        }
    })
