package no.nav.syfo.narmestelederrelasjon.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import org.slf4j.LoggerFactory
import java.util.UUID

class RevokeNarmestelederrelasjonLoggingContractTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(
            revokeAccessRejected,
            rejectionReasons = setOf("MISSING_ORG_ACCESS", "MISSING_ALITINN_RESOURCE_ACCESS"),
        )
        val logger = LoggerFactory.getLogger(RevokeNarmestelederrelasjon::class.java) as Logger
        val originalSettings = logger.level to logger.isAdditive
        val productionLogging = LoggerContext()
        lateinit var productionAppender: Appender<ILoggingEvent>
        lateinit var capture: LogCapture
        val id = UUID.fromString("00000000-0000-0000-0000-00000000abcd")
        val employee = PersonIdent("12345678901")
        val outsider = PersonIdent("11111111111")
        val org = OrganizationNumber("123456789")
        val user = OrganizationAccessSubject.PersonnelManager(outsider, AccessToken("test-token"))
        val system = OrganizationAccessSubject.LpsSystemUser("test-system-user", org)

        suspend fun execute(
            subject: OrganizationAccessSubject,
            denial: DenialReason,
            found: Boolean = true,
        ) = RevokeNarmestelederrelasjon(
            repository = object : NarmestelederrelasjonRepository {
                override suspend fun findById(id: UUID): NarmestelederrelasjonLookup? = error("Not used by revoke")
                override suspend fun findRevocableById(id: UUID) = if (found) {
                    RevocableNarmestelederrelasjon(id, employee, PersonIdent("10987654321"), org, isActive = false)
                } else {
                    null
                }
            },
            organizationAccess = { _, _ -> OrganizationAccessResult.Denied(denial) },
            publisher = { error("Publisher must not be called") },
        ).execute(id, subject)

        beforeSpec {
            productionLogging.putProperty("NAIS_CLUSTER_NAME", "test")
            JoranConfigurator().apply {
                context = productionLogging
                doConfigure("src/main/resources/logback.xml")
            }
            productionAppender = requireNotNull(productionLogging.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("stdout_json"))
            logger.level = Level.TRACE
            logger.isAdditive = false
            logger.addAppender(productionAppender)
        }
        afterSpec {
            logger.detachAppender(productionAppender)
            logger.level = originalSettings.first
            logger.isAdditive = originalSettings.second
            productionLogging.stop()
        }
        beforeTest { capture = captureLogs(logger, "stdout_json") }
        afterTest {
            try {
                capture.records.forEach { line ->
                    listOf(employee.value, outsider.value, org.value, "test-token", "test-system-user").forEach {
                        line shouldNotContain it
                    }
                }
            } finally {
                capture.close()
            }
        }

        test("missing organization and missing resource emit exactly the legacy revoke event") {
            listOf(
                DenialReason.MISSING_ORGANIZATION_ACCESS to "MISSING_ORG_ACCESS",
                DenialReason.MISSING_RESOURCE_ACCESS to "MISSING_ALITINN_RESOURCE_ACCESS",
            ).forEach { (reason, expected) ->
                execute(user, reason)
                contract.assertValid(capture.records, expectedCount = 1)
                val event = mapper.readTree(capture.records.single())
                event["event_type"].asText() shouldBe "api_request_rejected"
                event["operation"].asText() shouldBe "revoke_linemanager"
                event["rejection_reason"].asText() shouldBe expected
                event["narmesteleder_id"].asText() shouldBe id.toString()
                event["principal_type"].asText() shouldBe "UserPrincipal"
                capture.close()
                capture = captureLogs(logger, "stdout_json")
            }
        }

        test("a denied system user does not emit the revoke event") {
            DenialReason.entries.forEach { reason ->
                execute(system, reason)
                capture.records.size shouldBe 0
            }
        }

        test("unknown relation does not emit the revoke event") {
            execute(user, DenialReason.MISSING_ORGANIZATION_ACCESS, found = false)
            capture.records.size shouldBe 0
        }
    })
