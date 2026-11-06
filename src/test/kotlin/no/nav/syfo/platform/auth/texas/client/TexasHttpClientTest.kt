package no.nav.syfo.platform.auth.texas.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpStatusCode
import no.nav.syfo.integration.TEST_SYSTEM_TOKEN
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.platform.auth.texas.localTexasEnvironment
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class TexasHttpClientTest :
    FunSpec({
        test("Maskinporten uses the token endpoint and configured target without transforming it") {
            upstreamHttpClient(
                token = { request ->
                    request.url.toString() shouldBe localTexasEnvironment().tokenEndpoint
                    val body = jacksonObjectMapper().readTree(request.body.toByteArray())
                    body["identity_provider"].asText() shouldBe "maskinporten"
                    body["target"].asText() shouldBe "altinn:authorization/authorize"
                    respondWithSystemToken(request)
                },
                upstream = { error("Unexpected upstream request") },
            ).use {
                texasHttpClient(it).maskinportenSystemToken("altinn:authorization/authorize", TEST_TOKEN_TARGET) shouldBe UpstreamResult.Success(TEST_SYSTEM_TOKEN)
            }
        }

        listOf("invalid_grant" to "invalid_grant", "private-provider-message" to "unknown").forEach { (code, expected) ->
            test("Maskinporten retains only allowlisted OAuth code $expected") {
                upstreamHttpClient(
                    token = { respondJson("""{"error":"$code","error_description":"private-description"}""", HttpStatusCode.BadRequest) },
                    upstream = { error("Unexpected upstream request") },
                ).use {
                    assertSoftly(texasHttpClient(it).maskinportenSystemToken("target", TEST_TOKEN_TARGET).shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                        upstream shouldBe TEXAS
                        stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                        tokenTarget shouldBe TEST_TOKEN_TARGET
                        status shouldBe 400
                        errors shouldBe listOf(UpstreamError(expected))
                    }
                }
            }
        }

        test("Maskinporten malformed success retains response status") {
            upstreamHttpClient({ respondJson("""{"invalid":"response"}""") }, { error("Unexpected upstream request") }).use {
                assertSoftly(texasHttpClient(it).maskinportenSystemToken("target", TEST_TOKEN_TARGET).shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                    upstream shouldBe TEXAS
                    stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    tokenTarget shouldBe TEST_TOKEN_TARGET
                    status shouldBe 200
                }
            }
        }

        test("Maskinporten IO failure has no response status") {
            upstreamHttpClient({ throw IOException("Unavailable") }, { error("Unexpected upstream request") }).use {
                assertSoftly(texasHttpClient(it).maskinportenSystemToken("target", TEST_TOKEN_TARGET).shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                    upstream shouldBe TEXAS
                    stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    tokenTarget shouldBe TEST_TOKEN_TARGET
                    status shouldBe null
                }
            }
        }

        test("Maskinporten request cancellation is rethrown") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient({ throw cancelled }, { error("Unexpected upstream request") }).use {
                shouldThrow<CancellationException> { texasHttpClient(it).maskinportenSystemToken("target", TEST_TOKEN_TARGET) } shouldBe cancelled
            }
        }

        test("Maskinporten body cancellation is rethrown") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient(respondWithSystemToken) { error("Unexpected upstream request") }.use {
                it.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (subject.expectedType.type == TexasResponse::class) throw cancelled
                }
                shouldThrow<CancellationException> { texasHttpClient(it).maskinportenSystemToken("target", TEST_TOKEN_TARGET) } shouldBe cancelled
            }
        }

        test("TokenX exchange posts the configured target and user token and returns the access token") {
            val environment = localTexasEnvironment()
            upstreamHttpClient(
                token = { request ->
                    request.url.toString() shouldBe environment.tokenExchangeEndpoint
                    val body = jacksonObjectMapper().readTree(request.body.toByteArray())
                    body["identity_provider"].asText() shouldBe "tokenx"
                    body["target"].asText() shouldBe environment.exchangeTargetIsAltinnTilganger
                    body["user_token"].asText() shouldBe "synthetic-user-token"
                    respondWithSystemToken(request)
                },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                texasHttpClient(httpClient).exchangeToAltinnToken("synthetic-user-token", TEST_TOKEN_TARGET) shouldBe UpstreamResult.Success(TEST_SYSTEM_TOKEN)
            }
        }

        test("TokenX exchange retains only the restricted OAuth code from a Texas HTTP failure") {
            upstreamHttpClient(
                token = { respondJson("""{"error":"invalid_grant","error_description":"synthetic-private-description"}""", HttpStatusCode.BadRequest) },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                assertSoftly(texasHttpClient(httpClient).exchangeToAltinnToken("synthetic-user-token", TEST_TOKEN_TARGET).shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                    upstream shouldBe TEXAS
                    stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    tokenTarget shouldBe TEST_TOKEN_TARGET
                    status shouldBe 400
                    errors shouldBe listOf(UpstreamError("invalid_grant"))
                }
            }
        }

        test("TokenX exchange retains the Texas status when a successful response cannot be decoded") {
            upstreamHttpClient(
                token = { respondJson("""{"invalid":"response"}""") },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                assertSoftly(texasHttpClient(httpClient).exchangeToAltinnToken("synthetic-user-token", TEST_TOKEN_TARGET).shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                    upstream shouldBe TEXAS
                    stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    tokenTarget shouldBe TEST_TOKEN_TARGET
                    status shouldBe 200
                    errors shouldBe emptyList()
                    cause?.javaClass?.simpleName shouldBe "JsonConvertException"
                }
            }
        }

        test("TokenX exchange propagates cancellation during body decoding") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient(
                token = respondWithSystemToken,
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                httpClient.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (subject.expectedType.type == TexasResponse::class) throw cancelled
                }
                shouldThrow<CancellationException> {
                    texasHttpClient(httpClient).exchangeToAltinnToken("synthetic-user-token", TEST_TOKEN_TARGET)
                } shouldBe cancelled
            }
        }

        test("azureAdSystemToken posts the AzureAD identity provider and scoped target") {
            upstreamHttpClient(
                token = { request ->
                    val body = jacksonObjectMapper().readTree(request.body.toByteArray())
                    body["identity_provider"].asText() shouldBe "azuread"
                    body["target"].asText() shouldBe "api://scope/.default"
                    respondWithSystemToken(request)
                },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                texasHttpClient(httpClient).azureAdSystemToken("scope", TEST_TOKEN_TARGET) shouldBe UpstreamResult.Success(TEST_SYSTEM_TOKEN)
            }
        }

        test("azureAdSystemToken returns a Texas token exchange failure for a server error") {
            upstreamHttpClient(
                token = { respondJson("", HttpStatusCode.ServiceUnavailable) },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                assertSoftly(texasHttpClient(httpClient).azureAdSystemToken("scope", TEST_TOKEN_TARGET).shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                    upstream shouldBe TEXAS
                    stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    tokenTarget shouldBe TEST_TOKEN_TARGET
                    status shouldBe 503
                }
            }
        }

        test("azureAdSystemToken returns a Texas token exchange failure without status for IO errors") {
            val cause = IOException("Unavailable")
            upstreamHttpClient(
                token = { throw cause },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                assertSoftly(texasHttpClient(httpClient).azureAdSystemToken("scope", TEST_TOKEN_TARGET).shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                    upstream shouldBe TEXAS
                    stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    tokenTarget shouldBe TEST_TOKEN_TARGET
                    status shouldBe null
                    cause shouldBe cause
                }
            }
        }

        test("azureAdSystemToken returns a Texas token exchange failure with the response status for invalid bodies") {
            upstreamHttpClient(
                token = { respondJson("""{"invalid":"response"}""") },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                assertSoftly(texasHttpClient(httpClient).azureAdSystemToken("scope", TEST_TOKEN_TARGET).shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                    upstream shouldBe TEXAS
                    stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    tokenTarget shouldBe TEST_TOKEN_TARGET
                    status shouldBe 200
                }
            }
        }

        test("azureAdSystemToken rethrows cancellation") {
            val cause = CancellationException("Cancelled")
            upstreamHttpClient(
                token = { throw cause },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                shouldThrow<CancellationException> { texasHttpClient(httpClient).azureAdSystemToken("scope", TEST_TOKEN_TARGET) } shouldBe cause
            }
        }
    })

private val TEST_TOKEN_TARGET = UpstreamName("aareg")
