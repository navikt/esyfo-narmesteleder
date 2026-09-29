package no.nav.syfo.narmestelederrelasjon.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class HasActiveNarmestelederrelasjonUseCaseTest :
    FunSpec({
        val employee = PersonIdent("12345678901")
        val organization = OrganizationNumber("123456789")

        test("returns false when there are no active relations") {
            val repository = FakeActiveNarmestelederrelasjonRepository()

            HasActiveNarmestelederrelasjonUseCase(repository).execute(employee, organization) shouldBe false
            repository.lookups shouldBe listOf(employee to organization)
        }

        val relation = ActiveNarmestelederrelasjon(
            id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            managerIdent = PersonIdent("10987654321"),
            managerEmail = "leder@example.com",
            activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
        )

        test("returns true when one active relation exists") {
            val repository = FakeActiveNarmestelederrelasjonRepository()
            repository.rows = listOf(relation)

            HasActiveNarmestelederrelasjonUseCase(repository).execute(employee, organization) shouldBe true
            repository.lookups shouldBe listOf(employee to organization)
        }

        test("returns true when multiple active relations exist") {
            val repository = FakeActiveNarmestelederrelasjonRepository()
            repository.rows = listOf(
                relation,
                relation.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000002")),
            )

            HasActiveNarmestelederrelasjonUseCase(repository).execute(employee, organization) shouldBe true
        }

        test("does not parse the stored manager email") {
            val repository = FakeActiveNarmestelederrelasjonRepository()
            repository.rows = listOf(relation.copy(managerEmail = "leder@nav"))

            HasActiveNarmestelederrelasjonUseCase(repository).execute(employee, organization) shouldBe true
        }

        test("does not log multiple_active_relations when two rows exist") {
            val repository = FakeActiveNarmestelederrelasjonRepository()
            repository.rows = listOf(
                relation,
                relation.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000002")),
            )
            val logger = LoggerFactory.getLogger(LookupActiveNarmestelederUseCase::class.java) as Logger
            val originalSettings = logger.level to logger.isAdditive
            val productionLogging = LoggerContext()
            productionLogging.putProperty("NAIS_CLUSTER_NAME", "test")
            JoranConfigurator().apply {
                context = productionLogging
                doConfigure("src/main/resources/logback.xml")
            }
            val productionAppender: Appender<ILoggingEvent> =
                requireNotNull(productionLogging.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("stdout_json"))
            logger.level = Level.TRACE
            logger.isAdditive = false
            logger.addAppender(productionAppender)
            try {
                captureLogs(logger, "stdout_json").use { capture ->
                    HasActiveNarmestelederrelasjonUseCase(repository).execute(employee, organization) shouldBe true
                    capture.records.size shouldBe 0
                }
            } finally {
                logger.detachAppender(productionAppender)
                logger.level = originalSettings.first
                logger.isAdditive = originalSettings.second
                productionLogging.stop()
            }
        }
    })
