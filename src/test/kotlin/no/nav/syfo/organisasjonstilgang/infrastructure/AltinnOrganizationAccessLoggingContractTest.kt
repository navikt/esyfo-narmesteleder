package no.nav.syfo.organisasjonstilgang.infrastructure

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.altinn.pdp.client.DecisionResult
import no.nav.syfo.altinn.pdp.client.PdpClient
import no.nav.syfo.altinn.pdp.client.PdpResponse
import no.nav.syfo.altinn.pdp.client.User
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.ereg.client.Organisasjon
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import org.slf4j.LoggerFactory

class AltinnOrganizationAccessLoggingContractTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(
            systemUserAccessRejected,
            rejectionReasons = setOf(SYSTEM_USER_ACCESS_NOT_GRANTED),
        )
        val logger = LoggerFactory.getLogger(AltinnOrganizationAccess::class.java) as Logger
        val originalSettings = logger.level to logger.isAdditive
        val productionLogging = LoggerContext()
        lateinit var productionAppender: Appender<ILoggingEvent>
        lateinit var capture: LogCapture
        val privacyCanaries = listOf(REQUESTED_ORG, SYSTEM_USER_ORG, SYSTEM_USER_ID, PERSON_IDENT, ACCESS_TOKEN)

        fun rejectionRecord() = capture.records.single().let {
            contract.assertValid(capture.records, expectedCount = 1)
            mapper.readTree(it)
        }

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
        beforeTest {
            capture = captureLogs(logger, "stdout_json")
        }
        afterTest {
            try {
                capture.records.forEach { line -> privacyCanaries.forEach { line shouldNotContain it } }
            } finally {
                capture.close()
            }
        }

        test("logs one rejection without a fallback decision when the system user is outside the hierarchy") {
            val fixture = LoggingFixture(Decision.Indeterminate, parentOrgNumber = UNRELATED_ORG)

            fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

            val record = rejectionRecord()
            record["level"].asText() shouldBe "WARN"
            record["logger_name"].asText() shouldBe AltinnOrganizationAccess::class.java.name
            record["event_type"].asText() shouldBe "api_request_rejected"
            record["operation"].asText() shouldBe "validate_system_user_access"
            record["error_code"].asText() shouldBe "MISSING_ALTINN_RESOURCE_ACCESS"
            record["rejection_reason"].asText() shouldBe "SYSTEM_USER_ACCESS_NOT_GRANTED"
            record["pdp_decision"].asText() shouldBe "Indeterminate"
            record["pdp_fallback_decision"].asText() shouldBe "not_checked"
        }

        test("logs one rejection with the fallback decision when the system user is in the hierarchy") {
            val fixture = LoggingFixture(Decision.NotApplicable, Decision.Deny, parentOrgNumber = SYSTEM_USER_ORG)

            fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

            val record = rejectionRecord()
            record["pdp_decision"].asText() shouldBe "NotApplicable"
            record["pdp_fallback_decision"].asText() shouldBe "Deny"
        }

        test("logs nothing when the fallback grants access") {
            val fixture = LoggingFixture(Decision.Deny, Decision.Permit, parentOrgNumber = SYSTEM_USER_ORG)

            fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

            capture.records.shouldBeEmpty()
        }

        test("logs nothing when a personnel manager is denied") {
            val fixture = LoggingFixture(Decision.Deny, parentOrgNumber = UNRELATED_ORG)

            fixture.access.evaluate(
                OrganizationAccessSubject.PersonnelManager(PersonIdent(PERSON_IDENT), AccessToken(ACCESS_TOKEN)),
                OrganizationNumber(REQUESTED_ORG),
            )

            capture.records.shouldBeEmpty()
        }
    })

private const val REQUESTED_ORG = "910000011"
private const val SYSTEM_USER_ORG = "910000012"
private const val UNRELATED_ORG = "910000013"
private const val SYSTEM_USER_ID = "privacy-canary-system-user"
private const val PERSON_IDENT = "12345678902"
private const val ACCESS_TOKEN = "privacy-canary-token"

private class LoggingFixture(
    directDecision: Decision,
    fallbackDecision: Decision = Decision.Deny,
    parentOrgNumber: String,
) {
    private val decisions = mapOf(REQUESTED_ORG to directDecision, SYSTEM_USER_ORG to fallbackDecision)
    private val pdp = object : PdpClient {
        override suspend fun authorize(user: User, orgNumberSet: Set<String>, resource: String) = PdpResponse(listOf(DecisionResult(decisions.getValue(orgNumberSet.single()))))
    }
    private val ereg = FakeEregClient().also {
        it.organisasjoner.clear()
        it.organisasjoner[REQUESTED_ORG] = Organisasjon(
            organisasjonsnummer = REQUESTED_ORG,
            inngaarIJuridiskEnheter = listOf(Organisasjon(organisasjonsnummer = parentOrgNumber)),
        )
    }
    private val eregCache = mockk<EregCache> {
        every { getOrganisasjon(any()) } returns null
        every { putOrganisasjon(any(), any()) } just runs
    }
    val access = AltinnOrganizationAccess(
        AltinnTilgangerService(FakeAltinnTilgangerClient().also { it.accessPolicy.clear() }),
        PdpService(pdp),
        EregService(ereg, eregCache),
    )
}

private fun systemUser() = OrganizationAccessSubject.LpsSystemUser(
    systemUserId = SYSTEM_USER_ID,
    systemUserOrganizationNumber = OrganizationNumber(SYSTEM_USER_ORG),
)
