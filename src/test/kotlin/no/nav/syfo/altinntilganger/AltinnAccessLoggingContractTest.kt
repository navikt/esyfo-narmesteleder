package no.nav.syfo.altinntilganger

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import net.logstash.logback.encoder.LogstashEncoder
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.ALTINN_ACCESS_LOOKUP_FAILED_EVENT_TYPE
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.ALTINN_ACCESS_UPSTREAM_5XX_ERROR_CODE
import no.nav.syfo.altinntilganger.AltinnTilgangerService.Companion.LIST_ACCESSIBLE_ORGANIZATIONS_OPERATION
import no.nav.syfo.altinntilganger.client.AltinnTilgangerClient
import no.nav.syfo.application.api.STATUS_PAGES_LOGGER_NAME
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasResponse
import no.nav.syfo.util.httpClientDefault
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

class AltinnAccessLoggingContractTest :
    DescribeSpec({
        val logOutput = ByteArrayOutputStream()
        val serviceLogger = LoggerFactory.getLogger(AltinnTilgangerService::class.java) as Logger
        val statusPagesLogger = LoggerFactory.getLogger(STATUS_PAGES_LOGGER_NAME) as Logger
        val originalServiceLevel = serviceLogger.level
        val originalServiceAdditive = serviceLogger.isAdditive
        val originalStatusPagesLevel = statusPagesLogger.level
        val originalStatusPagesAdditive = statusPagesLogger.isAdditive
        val encoder = LogstashEncoder().apply {
            context = serviceLogger.loggerContext
            start()
        }
        val appender = OutputStreamAppender<ILoggingEvent>().apply {
            context = serviceLogger.loggerContext
            this.encoder = encoder
            setOutputStream(logOutput)
            start()
        }

        beforeSpec {
            serviceLogger.level = Level.TRACE
            serviceLogger.isAdditive = false
            serviceLogger.addAppender(appender)
            statusPagesLogger.level = Level.TRACE
            statusPagesLogger.isAdditive = false
            statusPagesLogger.addAppender(appender)
        }

        afterSpec {
            serviceLogger.detachAppender(appender)
            serviceLogger.level = originalServiceLevel
            serviceLogger.isAdditive = originalServiceAdditive
            statusPagesLogger.detachAppender(appender)
            statusPagesLogger.level = originalStatusPagesLevel
            statusPagesLogger.isAdditive = originalStatusPagesAdditive
            appender.stop()
            encoder.stop()
        }

        beforeTest {
            logOutput.reset()
        }

        describe("Altinn access lookup runtime error contract") {
            it("serializes one terminal error event through the production encoder without privacy canaries") {
                val nationalIdentificationNumberCanary = "12345678901"
                val tokenCanary = "privacy-canary-token"
                val emailCanary = "privacy-canary-email"
                val requestBodyCanary = "privacy-canary-request-body"
                val upstreamResponseCanary = "privacy-canary-upstream-response-body"
                val idCanary = "privacy-canary-id-8f9868ce"
                val texasClient = mockk<TexasHttpClient>()
                coEvery {
                    texasClient.exchangeTokenForIsAltinnTilganger(tokenCanary)
                } returns TexasResponse("safe-obo-token", 60, "Bearer")
                val upstreamEngine = MockEngine {
                    respond(
                        content = upstreamResponseCanary,
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                }
                val altinnClient = AltinnTilgangerClient(
                    texasClient = texasClient,
                    httpClient = httpClientDefault(HttpClient(upstreamEngine)),
                    baseUrl = "https://altinn-tilganger.test",
                )
                val service = AltinnTilgangerService(altinnClient)

                testApplication {
                    application {
                        installContentNegotiation()
                        installStatusPages()
                        routing {
                            get("/accessible-organizations/{ignored}") {
                                service.getFilteredOrganizations(
                                    UserPrincipal(nationalIdentificationNumberCanary, tokenCanary),
                                )
                                call.respond(HttpStatusCode.OK)
                            }
                        }
                    }

                    val response = client.get(
                        "/accessible-organizations/$nationalIdentificationNumberCanary" +
                            "?email=$emailCanary&payload=$requestBodyCanary&id=$idCanary",
                    )
                    response.status shouldBe HttpStatusCode.InternalServerError
                }

                val serializedLogs = logOutput.toString(Charsets.UTF_8)
                val logLines = serializedLogs.lineSequence().filter(String::isNotBlank).toList()
                logLines shouldHaveSize 1

                val logRecord = jacksonObjectMapper().readTree(logLines.single())
                logRecord["level"].asText() shouldBe "ERROR"
                logRecord["logger_name"].asText() shouldBe AltinnTilgangerService::class.java.name
                logRecord["message"].asText() shouldBe "Altinn access lookup failed"
                logRecord["event_type"].asText() shouldBe ALTINN_ACCESS_LOOKUP_FAILED_EVENT_TYPE
                logRecord["error_code"].asText() shouldBe ALTINN_ACCESS_UPSTREAM_5XX_ERROR_CODE
                logRecord["operation"].asText() shouldBe LIST_ACCESSIBLE_ORGANIZATIONS_OPERATION
                logRecord["exception_type"].asText() shouldBe "ServerResponseException"
                logRecord["stack_trace"].asText().contains("UpstreamRequestException") shouldBe true
                logRecord.has("status") shouldBe false
                logRecord.has("path") shouldBe false
                logRecord.has("url") shouldBe false
                logRecord.has("request") shouldBe false
                logRecord.has("response") shouldBe false

                listOf(
                    nationalIdentificationNumberCanary,
                    tokenCanary,
                    emailCanary,
                    requestBodyCanary,
                    upstreamResponseCanary,
                    idCanary,
                ).forEach { canary ->
                    serializedLogs shouldNotContain canary
                }
            }
        }
    })
