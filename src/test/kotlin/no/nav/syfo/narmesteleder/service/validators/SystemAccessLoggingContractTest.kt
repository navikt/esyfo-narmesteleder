package no.nav.syfo.narmesteleder.service.validators

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import net.logstash.logback.encoder.LogstashEncoder
import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.altinn.pdp.client.DecisionResult
import no.nav.syfo.altinn.pdp.client.IPdpClient
import no.nav.syfo.altinn.pdp.client.PdpResponse
import no.nav.syfo.altinn.pdp.client.User
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.application.api.STATUS_PAGES_LOGGER_NAME
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.ereg.client.Organisasjon
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

private const val REQUESTED_ORG = "111111111"
private const val PRINCIPAL_ORG = "222222222"
private val systemPrincipal = SystemPrincipal(
    ident = "0192:$PRINCIPAL_ORG",
    token = "privacy-canary-token",
    systemOwner = "0192:333333333",
    systemUserId = "privacy-canary-system-user",
)

class SystemAccessLoggingContractTest :
    DescribeSpec({
        val output = ByteArrayOutputStream()
        val emittedRejections = mutableListOf<String>()
        val catalog = requireNotNull(
            SystemAccessLoggingContractTest::class.java.getResourceAsStream("/observability/system-access-catalog.json"),
        ).use { jacksonObjectMapper().readTree(it) }
        val loggers = listOf(
            PrincipalAccessValidator.logger as Logger,
            LoggerFactory.getLogger(STATUS_PAGES_LOGGER_NAME) as Logger,
        )
        val originalSettings = loggers.map { it.level to it.isAdditive }
        val encoder = LogstashEncoder().apply {
            context = loggers.first().loggerContext
            start()
        }
        val appender = OutputStreamAppender<ILoggingEvent>().apply {
            context = loggers.first().loggerContext
            this.encoder = encoder
            setOutputStream(output)
            start()
        }
        val decisions = mutableMapOf<String, Decision>()
        val pdpFailures = mutableMapOf<String, Throwable>()
        val checkedOrganizations = mutableListOf<String>()
        val pdpClient = object : IPdpClient {
            override suspend fun authorize(user: User, orgNumberSet: Set<String>, resource: String): PdpResponse {
                val organization = orgNumberSet.single()
                checkedOrganizations += organization
                pdpFailures[organization]?.let { throw it }
                return PdpResponse(listOf(DecisionResult(decisions.getValue(organization))))
            }
        }
        val eregClient = FakeEregClient()
        val eregCache = mockk<EregCache>(relaxed = true)
        val validator = PrincipalAccessValidator(
            AltinnTilgangerService(FakeAltinnTilgangerClient()),
            PdpService(pdpClient),
            EregService(eregClient, eregCache),
        )

        fun checkAccessResponse(expectedStatus: HttpStatusCode) {
            testApplication {
                application {
                    installContentNegotiation()
                    installStatusPages()
                    routing {
                        get("/system-access") {
                            validator.validatePrincipalAccessToOrgnumber(systemPrincipal, REQUESTED_ORG)
                            call.respond(HttpStatusCode.OK)
                        }
                    }
                }
                val response = client.get("/system-access")
                response.status shouldBe expectedStatus
                if (expectedStatus == HttpStatusCode.Forbidden) {
                    val body = jacksonObjectMapper().readTree(response.bodyAsText())
                    body["type"].asText() shouldBe "MISSING_ALITINN_RESOURCE_ACCESS"
                    body["message"].asText() shouldBe
                        "System user does not have access to nav_syfo_oppgi-narmesteleder resource"
                }
            }
        }

        fun logRecords() = output.toString(Charsets.UTF_8)
            .lineSequence().filter(String::isNotBlank).map(jacksonObjectMapper()::readTree).toList()

        beforeSpec {
            loggers.forEach {
                it.level = Level.TRACE
                it.isAdditive = false
                it.addAppender(appender)
            }
        }
        afterSpec {
            loggers.zip(originalSettings).forEach { (logger, settings) ->
                logger.detachAppender(appender)
                logger.level = settings.first
                logger.isAdditive = settings.second
            }
            appender.stop()
            encoder.stop()
            val outputFile = Path.of("build/observability/system-access.ndjson")
            Files.createDirectories(outputFile.parent)
            Files.writeString(outputFile, emittedRejections.joinToString(separator = "\n", postfix = "\n"))
        }
        beforeTest {
            output.reset()
            decisions.clear()
            decisions[REQUESTED_ORG] = Decision.Indeterminate
            decisions[PRINCIPAL_ORG] = Decision.Deny
            checkedOrganizations.clear()
            pdpFailures.clear()
            eregClient.clearFailure()
            eregClient.organisasjoner[REQUESTED_ORG] = Organisasjon(organisasjonsnummer = REQUESTED_ORG)
            coEvery { eregCache.getOrganisasjon(any()) } returns null
        }
        afterTest {
            output.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).forEach { line ->
                if (jacksonObjectMapper().readTree(line).path("event_type").asText() == "api_request_rejected") {
                    val record = jacksonObjectMapper().readTree(line)
                    listOf("event_type", "error_code", "operation", "rejection_reason").forEach { field ->
                        catalog[field].any { it.asText() == record.path(field).asText() } shouldBe true
                    }
                    listOf(REQUESTED_ORG, PRINCIPAL_ORG, systemPrincipal.systemOwner, systemPrincipal.systemUserId, systemPrincipal.token)
                        .forEach { line shouldNotContain it }
                    emittedRejections += line
                }
            }
        }

        it("emits one structured terminal rejection retaining the PDP decision without identifiers") {
            checkAccessResponse(HttpStatusCode.Forbidden)

            val serialized = output.toString(Charsets.UTF_8)
            val records = logRecords()
            records shouldHaveSize 1
            val record = records.single()
            record["level"].asText() shouldBe "WARN"
            record.path("event_type").asText() shouldBe "api_request_rejected"
            record.path("rejection_reason").asText() shouldBe "SYSTEM_USER_ACCESS_NOT_GRANTED"
            record.path("error_code").asText() shouldBe "MISSING_ALTINN_RESOURCE_ACCESS"
            record.path("operation").asText() shouldBe "validate_system_user_access"
            record.path("pdp_decision").asText() shouldBe "Indeterminate"
            record.path("pdp_fallback_decision").asText() shouldBe "not_checked"
            listOf(REQUESTED_ORG, PRINCIPAL_ORG, systemPrincipal.systemOwner, systemPrincipal.systemUserId, systemPrincipal.token)
                .forEach { serialized shouldNotContain it }
        }

        Decision.entries.forEach { directDecision ->
            it("preserves direct $directDecision access without inventing a fallback decision") {
                decisions[REQUESTED_ORG] = directDecision

                checkAccessResponse(if (directDecision == Decision.Permit) HttpStatusCode.OK else HttpStatusCode.Forbidden)

                checkedOrganizations shouldBe listOf(REQUESTED_ORG)
                val records = logRecords()
                if (directDecision == Decision.Permit) {
                    records shouldHaveSize 0
                } else {
                    records shouldHaveSize 1
                    records.single()["pdp_decision"].asText() shouldBe directDecision.name
                    records.single()["pdp_fallback_decision"].asText() shouldBe "not_checked"
                }
            }

            Decision.entries.forEach { fallbackDecision ->
                it("preserves direct $directDecision and hierarchy $fallbackDecision authorization outcomes") {
                    decisions[REQUESTED_ORG] = directDecision
                    decisions[PRINCIPAL_ORG] = fallbackDecision
                    eregClient.organisasjoner[REQUESTED_ORG] = Organisasjon(
                        organisasjonsnummer = REQUESTED_ORG,
                        inngaarIJuridiskEnheter = listOf(Organisasjon(organisasjonsnummer = PRINCIPAL_ORG)),
                    )

                    val granted = directDecision == Decision.Permit || fallbackDecision == Decision.Permit
                    checkAccessResponse(if (granted) HttpStatusCode.OK else HttpStatusCode.Forbidden)

                    checkedOrganizations shouldBe if (directDecision == Decision.Permit) {
                        listOf(REQUESTED_ORG)
                    } else {
                        listOf(REQUESTED_ORG, PRINCIPAL_ORG)
                    }
                    val records = logRecords()
                    if (granted) {
                        records shouldHaveSize 0
                    } else {
                        records shouldHaveSize 1
                        records.single()["level"].asText() shouldBe "WARN"
                        records.single()["event_type"].asText() shouldBe "api_request_rejected"
                        records.single()["pdp_decision"].asText() shouldBe directDecision.name
                        records.single()["pdp_fallback_decision"].asText() shouldBe fallbackDecision.name
                    }
                }
            }
        }

        listOf(REQUESTED_ORG, PRINCIPAL_ORG).forEach { failingOrganization ->
            it("does not classify a PDP transport failure for $failingOrganization as a terminal access rejection") {
                eregClient.organisasjoner[REQUESTED_ORG] = Organisasjon(
                    organisasjonsnummer = REQUESTED_ORG,
                    inngaarIJuridiskEnheter = listOf(Organisasjon(organisasjonsnummer = PRINCIPAL_ORG)),
                )
                pdpFailures[failingOrganization] = UpstreamRequestException("PDP unavailable")

                checkAccessResponse(HttpStatusCode.InternalServerError)

                val records = logRecords()
                records shouldHaveSize 1
                records.single().has("event_type") shouldBe false
                records.single()["message"].asText() shouldBe "Unhandled API exception"
            }
        }

        it("does not report a terminal rejection when the organization hierarchy lookup fails") {
            eregClient.setFailure(UpstreamRequestException("Ereg unavailable"))

            checkAccessResponse(HttpStatusCode.InternalServerError)

            val records = logRecords()
            records shouldHaveSize 1
            records.single().has("event_type") shouldBe false
            checkedOrganizations shouldBe listOf(REQUESTED_ORG)
        }

        it("propagates cancellation without logging a rejection") {
            pdpFailures[REQUESTED_ORG] = CancellationException("Request cancelled")

            shouldThrow<CancellationException> {
                validator.validatePrincipalAccessToOrgnumber(systemPrincipal, REQUESTED_ORG)
            }

            logRecords() shouldHaveSize 0
        }

        it("keeps an existing trace id without synthesizing a new one") {
            val traceId = "0123456789abcdef0123456789abcdef"
            MDC.put("trace_id", traceId)
            try {
                shouldThrow<ApiErrorException.ForbiddenException> {
                    validator.validatePrincipalAccessToOrgnumber(systemPrincipal, REQUESTED_ORG)
                }
            } finally {
                MDC.remove("trace_id")
            }

            logRecords().single()["trace_id"].asText() shouldBe traceId
        }
    })
