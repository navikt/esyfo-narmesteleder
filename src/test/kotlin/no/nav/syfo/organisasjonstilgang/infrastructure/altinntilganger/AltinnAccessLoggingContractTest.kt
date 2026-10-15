package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import createMockToken
import defaultMocks
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.esyfo.observability.testkit.captureLogs
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.STATUS_PAGES_LOGGER_NAME
import no.nav.syfo.application.api.installContentNegotiation
import no.nav.syfo.application.api.installStatusPages
import no.nav.syfo.application.auth.AddTokenIssuerPlugin
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.narmestelederbehov.api.registerListNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDetails
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovEmployeeName
import no.nav.syfo.narmestelederbehov.application.OpenNarmestelederbehovRepository
import no.nav.syfo.organisasjonstilgang.api.registerAccessibleOrganizationsApi
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsUseCase
import no.nav.syfo.organisasjonstilgang.infrastructure.AltinnOrganizationAccess
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.FakeAltinnAuthorizationClient
import no.nav.syfo.texas.client.TexasHttpClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant

class AltinnAccessLoggingContractTest :
    FunSpec({
        val ident = "00000000000"
        val orgNumber = "910000001"
        val userToken = createMockToken(ident, issuer = "https://tokenx.nav.no")
        val privateBody = "synthetic-private-body $ident $orgNumber"
        val isErrorBody = """{
            "isError":true,
            "hierarki":[{"orgnr":"$orgNumber","navn":"synthetic-private-name","organisasjonsform":"BEDR",
            "altinn3Tilganger":["nav_syfo_oppgi-narmesteleder"],"underenheter":[]}],
            "orgNrTilTilganger":{},"tilgangTilOrgNr":{}
        }"""
        val oauthBody = """{"error":"invalid_grant","error_description":"synthetic-private-description $ident $orgNumber"}"""
        val privacyCanaries = listOf(ident, orgNumber, userToken, "system-token", "error_description", "synthetic-private-description", "synthetic-private-body", "synthetic-private-name", isErrorBody, oauthBody)
        val scenarios = listOf(
            FailureScenario("HTTP 403", upstream = { respondJson(privateBody, HttpStatusCode.Forbidden) }, status = 403),
            FailureScenario("HTTP 503", upstream = { respondJson(privateBody, HttpStatusCode.ServiceUnavailable) }, status = 503),
            FailureScenario("timeout", upstream = { throw SocketTimeoutException(privateBody) }),
            FailureScenario("IO error", upstream = { throw IOException(privateBody) }),
            FailureScenario("invalid body", upstream = { respondJson(privateBody) }, status = 200),
            FailureScenario("isError with data", upstream = { respondJson(isErrorBody) }, status = 200, errorCode = "is_error"),
            FailureScenario(
                "isError without data",
                upstream = { respondJson("""{"isError":true,"hierarki":[],"orgNrTilTilganger":{},"tilgangTilOrgNr":{}}""") },
                status = 200,
                errorCode = "is_error",
            ),
            FailureScenario(
                "OAuth token error",
                token = { respondJson(oauthBody, HttpStatusCode.BadRequest) },
                upstream = { error("Altinn must not be called after token failure") },
                status = 400,
                errorCode = "invalid_grant",
                upstreamName = "texas",
                stage = "token_exchange",
            ),
            FailureScenario(
                "invalid token body",
                token = { respondJson(privateBody) },
                upstream = { error("Altinn must not be called after token failure") },
                status = 200,
                upstreamName = "texas",
                stage = "token_exchange",
            ),
        )

        listOf("access check", "organizations").forEach { endpoint ->
            scenarios.forEach { scenario ->
                test("$endpoint returns 500 and logs ${scenario.name} once at StatusPages without sensitive data") {
                    val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
                    context.putProperty("NAIS_CLUSTER_NAME", "test")
                    JoranConfigurator().apply {
                        this.context = context
                        doConfigure("src/main/resources/logback.xml")
                    }
                    val jsonAppender = requireNotNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("stdout_json"))
                    val loggers = listOf(
                        STATUS_PAGES_LOGGER_NAME,
                        AltinnTilgangerService::class.java.name,
                        HttpAltinnTilgangerClient::class.java.name,
                        TexasHttpClient::class.java.name,
                        AltinnOrganizationAccess::class.java.name,
                        ListNarmestelederbehovUseCase::class.java.name,
                        ListAccessibleOrganizationsUseCase::class.java.name,
                    ).map { LoggerFactory.getLogger(it) as Logger }
                    val settings = loggers.map { it.level to it.isAdditive }
                    val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
                    val allLogs = ListAppender<ILoggingEvent>().apply { start() }
                    try {
                        rootLogger.addAppender(allLogs)
                        loggers.forEach {
                            it.level = Level.TRACE
                            it.isAdditive = false
                            it.addAppender(jsonAppender)
                            it.addAppender(allLogs)
                        }
                        captureLogs(loggers.first(), "stdout_json").use { capture ->
                            upstreamHttpClient(scenario.token, scenario.upstream).use { http ->
                                val service = AltinnTilgangerService(HttpAltinnTilgangerClient(texasHttpClient(http), http, "http://altinn"))
                                val access = AltinnOrganizationAccess(service, FakeAltinnAuthorizationClient(), FakeEregClient())
                                val authClient = mockk<TexasHttpClient>().also { it.defaultMocks(pid = ident, acr = "Level4") }
                                testApplication {
                                    application {
                                        installContentNegotiation()
                                        installStatusPages()
                                        routing {
                                            route(API_V1_PATH) {
                                                install(AddTokenIssuerPlugin)
                                                registerAccessibleOrganizationsApi(ListAccessibleOrganizationsUseCase(service), authClient)
                                                registerListNarmestelederbehovApi(
                                                    ListNarmestelederbehovUseCase(
                                                        UnreachableOpenRepository(),
                                                        access,
                                                        NarmestelederbehovEmployeeName(mockk(), EmployeeNameLookup { error("Name lookup must not be called") }),
                                                    ),
                                                    authClient,
                                                )
                                            }
                                        }
                                    }
                                    val path = if (endpoint == "organizations") {
                                        "$API_V1_PATH/access/organizations"
                                    } else {
                                        "$API_V1_PATH/linemanager/requirement?orgNumber=$orgNumber&createdAfter=1970-01-01T00:00:00Z"
                                    }
                                    val response = client.get(path) { bearerAuth(userToken) }
                                    response.status shouldBe HttpStatusCode.InternalServerError
                                    val body = jacksonObjectMapper().readTree(response.bodyAsText())
                                    body["type"].asText() shouldBe "UPSTREAM_SERVICE_UNAVAILABLE"
                                    body["message"].asText() shouldBe "An upstream service is unavailable"
                                }
                            }
                            RuntimeLogContract(
                                mapOf(
                                    "event_type" to setOf("api_request_failed"),
                                    "exception_type" to setOf("ApiErrorException\$InternalServerErrorException"),
                                )
                            ).assertValid(capture.records, expectedCount = 1)
                            val serialized = capture.records.single()
                            privacyCanaries.forEach { serialized shouldNotContain it }
                            val record = jacksonObjectMapper().readTree(serialized)
                            record["level"].asText() shouldBe "ERROR"
                            record["logger_name"].asText() shouldBe STATUS_PAGES_LOGGER_NAME
                            record["event_type"].asText() shouldBe "api_request_failed"
                            record["upstream"].asText() shouldBe scenario.upstreamName
                            record["failure_stage"].asText() shouldBe scenario.stage
                            record["upstream_status"]?.asInt() shouldBe scenario.status
                            if (scenario.errorCode != null) {
                                record["upstream_errors"][0]["code"].asText() shouldBe scenario.errorCode
                                record["upstream_errors"].size() shouldBe 1
                            } else {
                                record.has("upstream_errors") shouldBe false
                            }
                            allLogs.list.filter { it.level == Level.ERROR }.size shouldBe 1
                            allLogs.list.forEach { event ->
                                privacyCanaries.forEach { event.formattedMessage shouldNotContain it }
                            }
                        }
                    } finally {
                        rootLogger.detachAppender(allLogs)
                        loggers.forEachIndexed { index, logger ->
                            logger.detachAppender(jsonAppender)
                            logger.detachAppender(allLogs)
                            logger.level = settings[index].first
                            logger.isAdditive = settings[index].second
                        }
                        allLogs.stop()
                        context.stop()
                    }
                }
            }
        }
    })

private data class FailureScenario(
    val name: String,
    val token: MockRequestHandler = respondWithSystemToken,
    val upstream: MockRequestHandler,
    val status: Int? = null,
    val errorCode: String? = null,
    val upstreamName: String = "arbeidsgiver-altinn-tilganger",
    val stage: String = if (status == null) "request" else "response",
)

private class UnreachableOpenRepository : OpenNarmestelederbehovRepository {
    override suspend fun findOpen(organizationNumber: OrganizationNumber, createdAfter: Instant, limit: Int): List<NarmestelederbehovDetails> = error("Repository must not be called after access failure")

    override suspend fun countOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Long = error("Repository must not be called after access failure")
}
