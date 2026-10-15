package no.nav.syfo.texas.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpStatusCode
import no.nav.syfo.application.texas.TexasEnvironment
import no.nav.syfo.integration.TEST_SYSTEM_TOKEN
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class TexasHttpClientTest :
    FunSpec({
        test("TokenX exchange posts the configured target and user token and returns the access token") {
            val environment = TexasEnvironment.createForLocal()
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
                texasHttpClient(httpClient).exchangeTokenForIsAltinnTilganger("synthetic-user-token") shouldBe UpstreamResult.Success(TEST_SYSTEM_TOKEN)
            }
        }

        test("TokenX exchange retains only the restricted OAuth code from a Texas HTTP failure") {
            upstreamHttpClient(
                token = { respondJson("""{"error":"invalid_grant","error_description":"synthetic-private-description"}""", HttpStatusCode.BadRequest) },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                val failure = texasHttpClient(httpClient).exchangeTokenForIsAltinnTilganger("synthetic-user-token")
                    .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                failure.upstream shouldBe TEXAS
                failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                failure.status shouldBe 400
                failure.errors shouldBe listOf(UpstreamError("invalid_grant"))
            }
        }

        test("TokenX exchange retains the Texas status when a successful response cannot be decoded") {
            upstreamHttpClient(
                token = { respondJson("""{"invalid":"response"}""") },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                val failure = texasHttpClient(httpClient).exchangeTokenForIsAltinnTilganger("synthetic-user-token")
                    .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                failure.upstream shouldBe TEXAS
                failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                failure.status shouldBe 200
                failure.errors shouldBe emptyList()
                failure.cause?.javaClass?.simpleName shouldBe "JsonConvertException"
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
                    texasHttpClient(httpClient).exchangeTokenForIsAltinnTilganger("synthetic-user-token")
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
                texasHttpClient(httpClient).azureAdSystemToken("scope") shouldBe UpstreamResult.Success(TEST_SYSTEM_TOKEN)
            }
        }

        test("azureAdSystemToken returns a Texas token exchange failure for a server error") {
            upstreamHttpClient(
                token = { respondJson("", HttpStatusCode.ServiceUnavailable) },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                val failure = texasHttpClient(httpClient).azureAdSystemToken("scope").shouldBeInstanceOf<UpstreamResult.Failure>().failure
                failure.upstream shouldBe TEXAS
                failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                failure.status shouldBe 503
            }
        }

        test("azureAdSystemToken returns a Texas token exchange failure without status for IO errors") {
            val cause = IOException("Unavailable")
            upstreamHttpClient(
                token = { throw cause },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                val failure = texasHttpClient(httpClient).azureAdSystemToken("scope").shouldBeInstanceOf<UpstreamResult.Failure>().failure
                failure.upstream shouldBe TEXAS
                failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                failure.status shouldBe null
                failure.cause shouldBe cause
            }
        }

        test("azureAdSystemToken returns a Texas token exchange failure without status for invalid bodies") {
            upstreamHttpClient(
                token = { respondJson("""{"invalid":"response"}""") },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                val failure = texasHttpClient(httpClient).azureAdSystemToken("scope").shouldBeInstanceOf<UpstreamResult.Failure>().failure
                failure.upstream shouldBe TEXAS
                failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                failure.status shouldBe null
            }
        }

        test("azureAdSystemToken rethrows cancellation") {
            val cause = CancellationException("Cancelled")
            upstreamHttpClient(
                token = { throw cause },
                upstream = { error("Unexpected upstream request") },
            ).use { httpClient ->
                shouldThrow<CancellationException> { texasHttpClient(httpClient).azureAdSystemToken("scope") } shouldBe cause
            }
        }
    })
