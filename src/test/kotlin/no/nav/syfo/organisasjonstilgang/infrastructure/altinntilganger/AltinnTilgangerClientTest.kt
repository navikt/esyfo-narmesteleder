package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import no.nav.syfo.integration.TEST_SYSTEM_TOKEN
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.platform.auth.UserPrincipal
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

class AltinnTilgangerClientTest :
    FunSpec({
        val principal = UserPrincipal("00000000000", "synthetic-user-token")
        val expected = AltinnTilgangerResponse(false, emptyList(), emptyMap(), emptyMap())
        val json = jacksonObjectMapper().writeValueAsString(expected)

        suspend fun fetch(token: MockRequestHandler = respondWithSystemToken, upstream: MockRequestHandler): UpstreamResult<AltinnTilgangerResponse> = upstreamHttpClient(token, upstream).use {
            HttpAltinnTilgangerClient(texasHttpClient(it), it, "http://altinn").fetchAltinnTilganger(principal)
        }

        test("returns success using the exchanged access token") {
            fetch {
                it.method shouldBe HttpMethod.Post
                it.url.encodedPath shouldBe "/altinn-tilganger"
                it.headers[HttpHeaders.Authorization] shouldBe "Bearer $TEST_SYSTEM_TOKEN"
                respondJson(json)
            } shouldBe UpstreamResult.Success(expected)
        }

        test("a response without isError is successful") {
            fetch { respondJson("""{"hierarki":[],"orgNrTilTilganger":{},"tilgangTilOrgNr":{}}""") } shouldBe
                UpstreamResult.Success(expected.copy(isError = null))
        }

        listOf(
            """{"error":"invalid_grant","error_description":"synthetic-private-description"}""" to "invalid_grant",
            """{"error":"untrusted-code"}""" to "unknown",
            """{"error_description":"synthetic-private-description"}""" to "unknown",
            "not-json" to "unknown",
        ).forEach { (body, code) ->
            test("token error body maps to restricted OAuth code $code for $body") {
                val result = fetch(
                    token = { respondJson(body, HttpStatusCode.BadRequest) },
                    upstream = { error("Altinn must not be called after token failure") },
                ).shouldBeInstanceOf<UpstreamResult.Failure>()
                result.failure.upstream shouldBe UpstreamName("texas")
                result.failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                result.failure.status shouldBe 400
                result.failure.errors shouldBe listOf(UpstreamError(code))
                result.failure.cause.shouldBeInstanceOf<ResponseException>()
            }
        }

        test("a non-response token failure stops before requesting Altinn") {
            val result = fetch(
                token = { throw IOException("synthetic-token-failure") },
                upstream = { error("Altinn must not be called after token failure") },
            ).shouldBeInstanceOf<UpstreamResult.Failure>()
            result.failure.upstream shouldBe UpstreamName("texas")
            result.failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
            result.failure.status shouldBe null
            result.failure.errors shouldBe emptyList()
            result.failure.cause.shouldBeInstanceOf<IOException>()
        }

        listOf(HttpStatusCode.MovedPermanently, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable).forEach { status ->
            test("HTTP ${status.value} returns a response failure with status") {
                val result = fetch { respondJson("synthetic-private-body", status) }.shouldBeInstanceOf<UpstreamResult.Failure>()
                result.failure.upstream shouldBe ALTINN_TILGANGER
                result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
                result.failure.status shouldBe status.value
                result.failure.cause.shouldBeInstanceOf<ResponseException>()
            }
        }

        listOf(
            "timeout" to SocketTimeoutException("synthetic-private-request"),
            "IO failure" to IOException("synthetic-private-request"),
            "unexpected transport failure" to IllegalStateException("synthetic-private-request"),
        ).forEach { (description, cause) ->
            test("$description returns a request failure without status") {
                val result = fetch { throw cause }.shouldBeInstanceOf<UpstreamResult.Failure>()
                result.failure.upstream shouldBe ALTINN_TILGANGER
                result.failure.stage shouldBe UpstreamFailureStage.REQUEST
                result.failure.status shouldBe null
                result.failure.cause?.javaClass shouldBe cause.javaClass
            }
        }

        test("invalid response body returns a response failure with the actual status") {
            val result = fetch { respondJson("not-json", HttpStatusCode.Created) }.shouldBeInstanceOf<UpstreamResult.Failure>()
            result.failure.upstream shouldBe ALTINN_TILGANGER
            result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
            result.failure.status shouldBe 201
            result.failure.cause?.javaClass?.simpleName shouldBe "JsonConvertException"
        }

        listOf(emptyList(), listOf(AltinnTilgang("910000001", emptySet(), emptyList(), "Synthetic Org", "BEDR"))).forEach { hierarchy ->
            test("isError true is a response failure with ${hierarchy.size} hierarchy entries") {
                val body = jacksonObjectMapper().writeValueAsString(expected.copy(isError = true, hierarki = hierarchy))
                val result = fetch { respondJson(body) }.shouldBeInstanceOf<UpstreamResult.Failure>()
                result.failure.upstream shouldBe ALTINN_TILGANGER
                result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
                result.failure.status shouldBe 200
                result.failure.errors shouldBe listOf(UpstreamError("is_error"))
                result.failure.cause shouldBe null
            }
        }

        test("propagates token exchange cancellation without calling Altinn") {
            shouldThrow<CancellationException> {
                fetch(
                    token = { throw CancellationException("Cancelled") },
                    upstream = { error("Altinn must not be called") },
                )
            }
        }

        test("propagates request cancellation") {
            shouldThrow<CancellationException> { fetch { throw CancellationException("Cancelled") } }
        }

        test("propagates body decoding cancellation") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient(respondWithSystemToken) { respondJson(json) }.use { http ->
                http.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (subject.expectedType.type == AltinnTilgangerResponse::class) throw cancelled
                }
                shouldThrow<CancellationException> {
                    HttpAltinnTilgangerClient(texasHttpClient(http), http, "http://altinn").fetchAltinnTilganger(principal)
                } shouldBeSameInstanceAs cancelled
            }
        }

        test("any non-cancellation body failure is classified as RESPONSE") {
            val original = IllegalStateException("Synthetic decode failure")
            upstreamHttpClient(respondWithSystemToken) { respondJson(json) }.use { http ->
                http.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (subject.expectedType.type == AltinnTilgangerResponse::class) throw original
                }
                val result = HttpAltinnTilgangerClient(texasHttpClient(http), http, "http://altinn")
                    .fetchAltinnTilganger(principal).shouldBeInstanceOf<UpstreamResult.Failure>()
                result.failure.stage shouldBe UpstreamFailureStage.RESPONSE
                result.failure.status shouldBe 200
                result.failure.cause shouldBeSameInstanceAs original
            }
        }
    })
