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

class GetNarmestelederrelasjonLoggingContractTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(narmestelederrelasjonNotFound)
        val logger = LoggerFactory.getLogger(GetNarmestelederrelasjon::class.java) as Logger
        val originalSettings = logger.level to logger.isAdditive
        val productionLogging = LoggerContext()
        lateinit var productionAppender: Appender<ILoggingEvent>
        lateinit var capture: LogCapture

        val id = UUID.fromString("00000000-0000-0000-0000-00000000abcd")
        val employeeIdent = PersonIdent("12345678901")
        val callerIdent = PersonIdent("11111111111")
        val orgNumber = OrganizationNumber("123456789")
        val caller = OrganizationAccessSubject.PersonnelManager(callerIdent, AccessToken("test-token"))
        val privacyCanaries = listOf(
            id.toString(),
            employeeIdent.value,
            callerIdent.value,
            orgNumber.value,
            "test-token",
            "private-first-name",
            "private-last-name",
        )

        fun lookup(isActive: Boolean = true) = NarmestelederrelasjonLookup(
            id = id,
            organizationNumber = orgNumber,
            employeeIdent = employeeIdent,
            employeeFirstName = "private-first-name",
            employeeMiddleName = null,
            employeeLastName = "private-last-name",
            isActive = isActive,
        )

        fun useCase(
            lookup: NarmestelederrelasjonLookup? = lookup(),
            access: OrganizationAccessResult = OrganizationAccessResult.Granted,
            activeSykmelding: Boolean = true,
        ) = GetNarmestelederrelasjon(
            repository = object : NarmestelederrelasjonRepository {
                override suspend fun findById(id: UUID) = lookup
                override suspend fun findRevocableById(id: UUID): RevocableNarmestelederrelasjon? = error("Not used by GET")
            },
            organizationAccess = { _, _ -> access },
            activeSykmeldingLookup = { _, _ -> activeSykmelding },
            organization = object : NarmestelederrelasjonOrganization {
                override suspend fun findName(orgNumber: OrganizationNumber) = "Organization"
            },
        )

        fun checkNotFoundEvent(outcomeCode: String) = mapper.readTree(capture.records.single()).also {
            contract.assertValid(capture.records, expectedCount = 1)
            it["event_type"].asText() shouldBe "narmestelederrelasjon_not_found"
            it["level"].asText() shouldBe "WARN"
            it["operation"].asText() shouldBe "get_narmestelederrelasjon"
            it["outcome_code"].asText() shouldBe outcomeCode
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

        test("logs RELATION_NOT_FOUND when no relation exists for the id") {
            useCase(lookup = null).execute(id, caller)
            checkNotFoundEvent("RELATION_NOT_FOUND").has("denial_reason") shouldBe false
        }

        test("logs RELATION_INACTIVE when the relation is no longer active") {
            useCase(lookup = lookup(isActive = false)).execute(id, caller)
            checkNotFoundEvent("RELATION_INACTIVE")
        }

        test("logs ACCESS_DENIED with denial reason") {
            useCase(access = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)).execute(id, caller)
            checkNotFoundEvent("ACCESS_DENIED")["denial_reason"].asText() shouldBe "MISSING_ORGANIZATION_ACCESS"
        }

        test("logs NO_ACTIVE_SYKMELDING when the employee has no active sykmelding") {
            useCase(activeSykmelding = false).execute(id, caller)
            checkNotFoundEvent("NO_ACTIVE_SYKMELDING")
        }

        test("does not log a not-found event when the relation is returned") {
            useCase().execute(id, caller)
            capture.records.size shouldBe 0
        }
    })
