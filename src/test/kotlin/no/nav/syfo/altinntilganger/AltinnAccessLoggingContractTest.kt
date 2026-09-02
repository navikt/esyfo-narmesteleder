package no.nav.syfo.altinntilganger

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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.ContentConverter
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import net.logstash.logback.encoder.LogstashEncoder
import no.nav.syfo.altinntilganger.client.AltinnTilgangerClient
import no.nav.syfo.altinntilganger.client.AltinnTilgangerResponse
import no.nav.syfo.altinntilganger.client.IAltinnTilgangerClient
import no.nav.syfo.application.api.STATUS_PAGES_LOGGER_NAME
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamExceptionType
import no.nav.syfo.application.exception.UpstreamFailureStage
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasResponse
import no.nav.syfo.util.httpClientDefault
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

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

        describe("AltinnTilganger runtime error contract") {
            it("serializes one terminal error event through the production encoder without privacy canaries") {
                val nationalIdentificationNumberCanary = "12345678901"
                val tokenCanary = "privacy-canary-token"
                val oboTokenCanary = "safe-obo-token"
                val emailCanary = "privacy-canary-email"
                val requestBodyCanary = "privacy-canary-request-body"
                val upstreamResponseCanary = "privacy-canary-upstream-response-body"
                val idCanary = "privacy-canary-id-8f9868ce"
                val texasClient = mockk<TexasHttpClient>()
                coEvery {
                    texasClient.exchangeTokenForIsAltinnTilganger(tokenCanary)
                } returns TexasResponse(oboTokenCanary, 60, "Bearer")
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
                logRecord["message"].asText() shouldBe "AltinnTilganger lookup failed"
                logRecord["event_type"].asText() shouldBe AltinnTilgangerRuntimeEvent.LOOKUP_FAILED.value
                logRecord["error_code"].asText() shouldBe AltinnTilgangerErrorCode.UPSTREAM_SERVER_ERROR.value
                logRecord["operation"].asText() shouldBe AltinnTilgangerOperation.LIST_ACCESSIBLE_ORGANIZATIONS.value
                logRecord["exception_type"].asText() shouldBe "ServerResponseException"
                logRecord["failure_stage"].asText() shouldBe "response"
                logRecord["upstream_status"].isInt shouldBe true
                logRecord["upstream_status"].asInt() shouldBe 503
                logRecord.has("stack_trace") shouldBe false
                logRecord.has("status") shouldBe false
                logRecord.has("path") shouldBe false
                logRecord.has("url") shouldBe false
                logRecord.has("request") shouldBe false
                logRecord.has("response") shouldBe false

                listOf(
                    nationalIdentificationNumberCanary,
                    tokenCanary,
                    oboTokenCanary,
                    emailCanary,
                    requestBodyCanary,
                    upstreamResponseCanary,
                    idCanary,
                ).forEach { canary ->
                    serializedLogs shouldNotContain canary
                }
            }

            it("keeps actionable HTTP distinctions and serializes bounded upstream status as a number") {
                val cases = listOf(
                    301 to AltinnTilgangerErrorCode.UPSTREAM_UNEXPECTED_REDIRECT,
                    401 to AltinnTilgangerErrorCode.UPSTREAM_UNAUTHORIZED,
                    403 to AltinnTilgangerErrorCode.UPSTREAM_FORBIDDEN,
                    404 to AltinnTilgangerErrorCode.UPSTREAM_NOT_FOUND,
                    429 to AltinnTilgangerErrorCode.UPSTREAM_RATE_LIMITED,
                    500 to AltinnTilgangerErrorCode.UPSTREAM_SERVER_ERROR,
                    502 to AltinnTilgangerErrorCode.UPSTREAM_SERVER_ERROR,
                    503 to AltinnTilgangerErrorCode.UPSTREAM_SERVER_ERROR,
                    504 to AltinnTilgangerErrorCode.UPSTREAM_SERVER_ERROR,
                )

                cases.forEach { (status, expectedErrorCode) ->
                    logOutput.reset()
                    val client = object : IAltinnTilgangerClient {
                        override suspend fun fetchAltinnTilganger(bruker: UserPrincipal): AltinnTilgangerResponse? = throw
                            UpstreamRequestException(
                                message = "Safe upstream failure",
                                upstreamStatus = status,
                                upstreamExceptionType = UpstreamExceptionType.RESPONSE_EXCEPTION,
                                failureStage = UpstreamFailureStage.RESPONSE,
                            )
                    }

                    shouldThrow<ApiErrorException.InternalServerErrorException> {
                        AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token"))
                    }

                    val logLines = logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
                    logLines shouldHaveSize 1
                    val logRecord = jacksonObjectMapper().readTree(logLines.single())
                    logRecord["error_code"].asText() shouldBe expectedErrorCode.value
                    logRecord["upstream_status"].isInt shouldBe true
                    logRecord["upstream_status"].asInt() shouldBe status
                    logRecord["failure_stage"].asText() shouldBe UpstreamFailureStage.RESPONSE.logValue
                }
            }

            it("uses the remaining client-error code for other 4xx statuses") {
                val client = object : IAltinnTilgangerClient {
                    override suspend fun fetchAltinnTilganger(bruker: UserPrincipal): AltinnTilgangerResponse? = throw
                        UpstreamRequestException(
                            message = "Safe upstream failure",
                            upstreamStatus = 422,
                            upstreamExceptionType = UpstreamExceptionType.CLIENT_REQUEST_EXCEPTION,
                            failureStage = UpstreamFailureStage.RESPONSE,
                        )
                }

                shouldThrow<ApiErrorException.InternalServerErrorException> {
                    AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token"))
                }

                val logRecord = jacksonObjectMapper().readTree(
                    logOutput.toString(Charsets.UTF_8).lineSequence().single(String::isNotBlank),
                )
                logRecord["error_code"].asText() shouldBe AltinnTilgangerErrorCode.UPSTREAM_CLIENT_ERROR.value
                logRecord["upstream_status"].asInt() shouldBe 422
            }

            it("omits upstream status for non-HTTP failures") {
                val client = object : IAltinnTilgangerClient {
                    override suspend fun fetchAltinnTilganger(bruker: UserPrincipal): AltinnTilgangerResponse? = throw
                        UpstreamRequestException(
                            message = "Safe transport failure",
                            upstreamStatus = 999,
                            upstreamExceptionType = UpstreamExceptionType.TRANSPORT_EXCEPTION,
                            failureStage = UpstreamFailureStage.REQUEST,
                        )
                }

                shouldThrow<ApiErrorException.InternalServerErrorException> {
                    AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token"))
                }

                val logRecord = jacksonObjectMapper().readTree(
                    logOutput.toString(Charsets.UTF_8).lineSequence().single(String::isNotBlank),
                )
                logRecord["error_code"].asText() shouldBe AltinnTilgangerErrorCode.UPSTREAM_TRANSPORT_FAILURE.value
                logRecord.has("upstream_status") shouldBe false
            }

            it("keeps token exchange separate while preserving its bounded HTTP status") {
                val client = object : IAltinnTilgangerClient {
                    override suspend fun fetchAltinnTilganger(bruker: UserPrincipal): AltinnTilgangerResponse? = throw
                        UpstreamRequestException(
                            message = "Safe token exchange failure",
                            upstreamStatus = 401,
                            upstreamExceptionType = UpstreamExceptionType.CLIENT_REQUEST_EXCEPTION,
                            failureStage = UpstreamFailureStage.TOKEN_EXCHANGE,
                        )
                }

                shouldThrow<ApiErrorException.InternalServerErrorException> {
                    AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token"))
                }

                val logRecord = jacksonObjectMapper().readTree(
                    logOutput.toString(Charsets.UTF_8).lineSequence().single(String::isNotBlank),
                )
                logRecord["error_code"].asText() shouldBe AltinnTilgangerErrorCode.TOKEN_EXCHANGE_FAILED.value
                logRecord["upstream_status"].isInt shouldBe true
                logRecord["upstream_status"].asInt() shouldBe 401
                logRecord["failure_stage"].asText() shouldBe UpstreamFailureStage.TOKEN_EXCHANGE.logValue
            }

            it("preserves nullable client results without emitting an error") {
                val client = object : IAltinnTilgangerClient {
                    override suspend fun fetchAltinnTilganger(bruker: UserPrincipal): AltinnTilgangerResponse? = null
                }
                val service = AltinnTilgangerService(client)
                val principal = UserPrincipal("12345678901", "token")

                service.getAltinnTilgangForOrgnr(principal, "999999999") shouldBe null
                service.getFilteredOrganizations(principal) shouldBe emptyList()

                val logLines = logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
                logLines shouldBe emptyList()
            }

            it("emits one terminal response-decoding error for an actual HTTP 200 JSON null body") {
                val texasClient = mockk<TexasHttpClient>()
                coEvery {
                    texasClient.exchangeTokenForIsAltinnTilganger("token")
                } returns TexasResponse("obo-token", 60, "Bearer")
                val upstreamEngine = MockEngine {
                    respond(
                        content = "null",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                val client = AltinnTilgangerClient(
                    texasClient = texasClient,
                    httpClient = httpClientDefault(HttpClient(upstreamEngine)),
                    baseUrl = "https://altinn-tilganger.test",
                )

                val exception = shouldThrow<ApiErrorException.InternalServerErrorException> {
                    AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token"))
                }

                exception.isAlreadyLogged shouldBe true
                val logLines = logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
                logLines shouldHaveSize 1
                val logRecord = jacksonObjectMapper().readTree(logLines.single())
                logRecord["error_code"].asText() shouldBe AltinnTilgangerErrorCode.UPSTREAM_RESPONSE_FAILURE.value
                logRecord["upstream_status"].isInt shouldBe true
                logRecord["upstream_status"].asInt() shouldBe 200
                logRecord["exception_type"].asText() shouldBe UpstreamExceptionType.RESPONSE_DECODING_EXCEPTION.logValue
                logRecord["failure_stage"].asText() shouldBe UpstreamFailureStage.RESPONSE.logValue
            }

            it("does not log or classify cancellation from token exchange") {
                val texasClient = mockk<TexasHttpClient>()
                coEvery {
                    texasClient.exchangeTokenForIsAltinnTilganger("token")
                } throws CancellationException("Token exchange cancelled")
                val client = AltinnTilgangerClient(
                    texasClient = texasClient,
                    httpClient = HttpClient(MockEngine { error("AltinnTilganger must not be called") }),
                    baseUrl = "https://altinn-tilganger.test",
                )

                shouldThrow<CancellationException> {
                    AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token"))
                }

                logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList() shouldHaveSize 0
            }

            it("does not log or classify cancellation from response decoding") {
                val cancellation = CancellationException("Response decoding cancelled")
                val cancellingConverter = object : ContentConverter {
                    override suspend fun serialize(
                        contentType: ContentType,
                        charset: Charset,
                        typeInfo: TypeInfo,
                        value: Any?,
                    ): OutgoingContent? = null

                    override suspend fun deserialize(
                        charset: Charset,
                        typeInfo: TypeInfo,
                        content: ByteReadChannel,
                    ): Any? = throw cancellation
                }
                val texasClient = mockk<TexasHttpClient>()
                coEvery {
                    texasClient.exchangeTokenForIsAltinnTilganger("token")
                } returns TexasResponse("obo-token", 60, "Bearer")
                val upstreamEngine = MockEngine {
                    respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                val httpClient = HttpClient(upstreamEngine) {
                    expectSuccess = true
                    install(ContentNegotiation) {
                        register(ContentType.Application.Json, cancellingConverter)
                    }
                }
                val client = AltinnTilgangerClient(
                    texasClient = texasClient,
                    httpClient = httpClient,
                    baseUrl = "https://altinn-tilganger.test",
                )

                shouldThrow<CancellationException> {
                    AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token"))
                }

                logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList() shouldHaveSize 0
            }

            it("emits the canonical terminal event when the upstream response reports an error") {
                val client = object : IAltinnTilgangerClient {
                    override suspend fun fetchAltinnTilganger(bruker: UserPrincipal) = AltinnTilgangerResponse(
                        isError = true,
                        hierarki = emptyList(),
                        orgNrTilTilganger = emptyMap(),
                        tilgangTilOrgNr = emptyMap(),
                    )
                }

                AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token")) shouldBe emptyList()

                val logLines = logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
                logLines shouldHaveSize 1
                val logRecord = jacksonObjectMapper().readTree(logLines.single())
                logRecord["level"].asText() shouldBe "ERROR"
                logRecord["event_type"].asText() shouldBe AltinnTilgangerRuntimeEvent.LOOKUP_FAILED.value
                logRecord["error_code"].asText() shouldBe AltinnTilgangerErrorCode.ERROR_RESPONSE.value
                logRecord["operation"].asText() shouldBe AltinnTilgangerOperation.LIST_ACCESSIBLE_ORGANIZATIONS.value
                logRecord.has("exception_type") shouldBe false
                logRecord.has("stack_trace") shouldBe false
            }

            it("serializes trace_id from MDC with the production encoder") {
                val traceId = "0123456789abcdef0123456789abcdef"
                val client = object : IAltinnTilgangerClient {
                    override suspend fun fetchAltinnTilganger(bruker: UserPrincipal): AltinnTilgangerResponse? = throw UpstreamRequestException(
                        message = "Upstream unavailable",
                        upstreamStatus = 503,
                        upstreamExceptionType = UpstreamExceptionType.SERVER_RESPONSE_EXCEPTION,
                    )
                }

                MDC.put("trace_id", traceId)
                try {
                    shouldThrow<ApiErrorException.InternalServerErrorException> {
                        AltinnTilgangerService(client).getFilteredOrganizations(UserPrincipal("12345678901", "token"))
                    }
                } finally {
                    MDC.remove("trace_id")
                }

                val logLines = logOutput.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).toList()
                logLines shouldHaveSize 1
                val logRecord = jacksonObjectMapper().readTree(logLines.single())
                logRecord["trace_id"].asText() shouldBe traceId
                Regex("^[0-9a-f]{32}$").matches(logRecord["trace_id"].asText()) shouldBe true
            }

            it("keeps event types, operations, error codes and exception types in closed valid catalogs") {
                val eventTypePattern = Regex("^[a-z][a-z0-9_.-]{0,79}$")
                val operationPattern = Regex("^[a-z][a-z0-9_.-]{0,79}$")
                val errorCodePattern = Regex("^[A-Z][A-Z0-9_]{0,79}$")
                val exceptionTypePattern = Regex("^[A-Za-z][A-Za-z0-9]{0,79}$")

                AltinnTilgangerRuntimeEvent.values().map { it.value }.toSet() shouldBe setOf(
                    "altinn_tilganger_lookup_failed",
                )
                AltinnTilgangerOperation.values().map { it.value }.toSet() shouldBe setOf(
                    "hent_altinn_tilgang_for_orgnummer",
                    "hent_tilgjengelige_organisasjoner",
                )
                AltinnTilgangerErrorCode.values().map { it.value }.toSet() shouldBe setOf(
                    "ALTINN_TILGANGER_UPSTREAM_UNEXPECTED_REDIRECT",
                    "ALTINN_TILGANGER_UPSTREAM_UNAUTHORIZED",
                    "ALTINN_TILGANGER_UPSTREAM_FORBIDDEN",
                    "ALTINN_TILGANGER_UPSTREAM_NOT_FOUND",
                    "ALTINN_TILGANGER_UPSTREAM_RATE_LIMITED",
                    "ALTINN_TILGANGER_UPSTREAM_CLIENT_ERROR",
                    "ALTINN_TILGANGER_UPSTREAM_SERVER_ERROR",
                    "ALTINN_TILGANGER_UPSTREAM_TRANSPORT_FAILURE",
                    "ALTINN_TILGANGER_UPSTREAM_RESPONSE_FAILURE",
                    "ALTINN_TILGANGER_TOKEN_EXCHANGE_FAILED",
                    "ALTINN_TILGANGER_ERROR_RESPONSE",
                )
                UpstreamExceptionType.values().map { it.logValue }.toSet() shouldBe setOf(
                    "ClientRequestException",
                    "ServerResponseException",
                    "RedirectResponseException",
                    "ResponseException",
                    "TransportException",
                    "ResponseDecodingException",
                    "UnexpectedException",
                )

                AltinnTilgangerRuntimeEvent.values().map { it.value }.distinct().size shouldBe
                    AltinnTilgangerRuntimeEvent.values().size
                AltinnTilgangerOperation.values().map { it.value }.distinct().size shouldBe
                    AltinnTilgangerOperation.values().size
                AltinnTilgangerErrorCode.values().map { it.value }.distinct().size shouldBe
                    AltinnTilgangerErrorCode.values().size
                UpstreamExceptionType.values().map { it.logValue }.distinct().size shouldBe
                    UpstreamExceptionType.values().size

                AltinnTilgangerRuntimeEvent.values().forEach { eventTypePattern.matches(it.value) shouldBe true }
                AltinnTilgangerOperation.values().forEach { operationPattern.matches(it.value) shouldBe true }
                AltinnTilgangerErrorCode.values().forEach { errorCodePattern.matches(it.value) shouldBe true }
                UpstreamExceptionType.values().forEach { exceptionTypePattern.matches(it.logValue) shouldBe true }
            }
        }
    })
