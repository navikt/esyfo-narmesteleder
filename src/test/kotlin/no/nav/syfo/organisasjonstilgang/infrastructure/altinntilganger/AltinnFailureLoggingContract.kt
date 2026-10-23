package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.mockk.mockk
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.createMockToken
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.organisasjonstilgang.infrastructure.AltinnOrganizationAccess
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.FakeAltinnAuthorizationClient
import no.nav.syfo.platform.api.API_V1_PATH
import no.nav.syfo.platform.api.STATUS_PAGES_LOGGER_NAME
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.api.testRouteApplication
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.auth.texas.stubTokenX
import no.nav.syfo.platform.observability.ProductionLogs
import java.io.IOException
import java.net.SocketTimeoutException

internal const val ALTINN_FAILURE_ORG_NUMBER = "910000001"
private const val IDENT = "00000000000"

/** The real Altinn adapters over a failing upstream, as a capability route uses them. */
internal class AltinnFailureUpstream(
    val tilganger: AltinnTilgangerService,
    val organizationAccess: AltinnOrganizationAccess,
    val authClient: TexasHttpClient,
)

/**
 * Registers one test per Altinn failure: the route at [path] returns a generic 500, and StatusPages logs
 * the upstream failure once, without identifiers or upstream bodies.
 */
internal fun FunSpec.altinnFailureLoggingContract(
    endpoint: String,
    path: String,
    capabilityLoggers: List<String>,
    routes: Route.(AltinnFailureUpstream) -> Unit,
) {
    val userToken = createMockToken(IDENT, issuer = "https://tokenx.nav.no")
    val privateBody = "synthetic-private-body $IDENT $ALTINN_FAILURE_ORG_NUMBER"
    val isErrorBody = """{
        "isError":true,
        "hierarki":[{"orgnr":"$ALTINN_FAILURE_ORG_NUMBER","navn":"synthetic-private-name","organisasjonsform":"BEDR",
        "altinn3Tilganger":["nav_syfo_oppgi-narmesteleder"],"underenheter":[]}],
        "orgNrTilTilganger":{},"tilgangTilOrgNr":{}
    }"""
    val oauthBody = """{"error":"invalid_grant","error_description":"synthetic-private-description $IDENT $ALTINN_FAILURE_ORG_NUMBER"}"""
    val privacyCanaries = listOf(
        IDENT,
        ALTINN_FAILURE_ORG_NUMBER,
        userToken,
        "system-token",
        "error_description",
        "synthetic-private-description",
        "synthetic-private-body",
        "synthetic-private-name",
        isErrorBody,
        oauthBody,
    )
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

    scenarios.forEach { scenario ->
        test("$endpoint returns 500 and logs ${scenario.name} once at StatusPages without sensitive data") {
            val productionLogs = ProductionLogs(
                listOf(
                    STATUS_PAGES_LOGGER_NAME,
                    AltinnTilgangerService::class.java.name,
                    HttpAltinnTilgangerClient::class.java.name,
                    TexasHttpClient::class.java.name,
                    AltinnOrganizationAccess::class.java.name,
                ) + capabilityLoggers,
            )
            val otherLoggerErrors = ProductionLogs(listOf(Logger.ROOT_LOGGER_NAME), Level.ERROR)
            productionLogs.install()
            otherLoggerErrors.install()
            try {
                productionLogs.capture().use { capture ->
                    otherLoggerErrors.capture().use { rootCapture ->
                        upstreamHttpClient(scenario.token, scenario.upstream).use { http ->
                            val service = AltinnTilgangerService(HttpAltinnTilgangerClient(texasHttpClient(http), http, "http://altinn"))
                            val upstream = AltinnFailureUpstream(
                                tilganger = service,
                                organizationAccess = AltinnOrganizationAccess(service, FakeAltinnAuthorizationClient(), FakeEregClient()),
                                authClient = mockk<TexasHttpClient>().also { it.stubTokenX(pid = IDENT) },
                            )
                            testRouteApplication(routes = { apiV1 { routes(upstream) } }) {
                                val response = client.get("$API_V1_PATH$path") { bearerAuth(userToken) }
                                response.status shouldBe HttpStatusCode.InternalServerError
                                val body = jacksonObjectMapper().readTree(response.bodyAsText())
                                body["type"].asText() shouldBe "UPSTREAM_SERVICE_UNAVAILABLE"
                                body["message"].asText() shouldBe "An upstream service is unavailable"
                            }
                        }
                        val allLogs = capture.logs + rootCapture.logs
                        val statusPagesRecords = allLogs.filter { it.loggerName == STATUS_PAGES_LOGGER_NAME }.map { it.serialized }
                        RuntimeLogContract(
                            mapOf(
                                "event_type" to setOf("api_request_failed"),
                                "exception_type" to setOf($$"ApiErrorException$InternalServerErrorException"),
                            )
                        ).assertValid(statusPagesRecords, expectedCount = 1)
                        val serialized = statusPagesRecords.single()
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
                        allLogs.filter { it.level == Level.ERROR }.size shouldBe 1
                        allLogs.filter { it.eventType == "api_request_rejected" }.size shouldBe 0
                        allLogs.forEach { log ->
                            privacyCanaries.forEach { log.serialized shouldNotContain it }
                        }
                    }
                }
            } finally {
                otherLoggerErrors.restore()
                productionLogs.restore()
            }
        }
    }
}

private data class FailureScenario(
    val name: String,
    val token: MockRequestHandler = respondWithSystemToken,
    val upstream: MockRequestHandler,
    val status: Int? = null,
    val errorCode: String? = null,
    val upstreamName: String = "arbeidsgiver-altinn-tilganger",
    val stage: String = if (status == null) "request" else "response",
)
