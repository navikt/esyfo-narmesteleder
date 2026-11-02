package no.nav.syfo.platform.auth.texas

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpStatusCode
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.platform.auth.texas.client.TEXAS
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class TexasAltinnTokenFetcherTest :
    FunSpec({
        test("Texas failure is propagated with the Altinn authentication token target and skips Altinn") {
            upstreamHttpClient(
                token = { respondJson("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) },
                upstream = { error("Altinn must not be called") },
            ).use {
                assertSoftly(TexasAltinnTokenFetcher(texasHttpClient(it), it, "https://altinn.test").token("target").shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                    upstream shouldBe TEXAS
                    stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    tokenTarget shouldBe ALTINN_AUTHENTICATION
                    status shouldBe 400
                    errors shouldBe listOf(UpstreamError("invalid_grant"))
                }
            }
        }

        listOf(400, 503).forEach { expectedStatus ->
            test("exchange HTTP $expectedStatus retains status and token exchange stage") {
                upstreamHttpClient(respondWithSystemToken) { respondJson("private-response", HttpStatusCode.fromValue(expectedStatus)) }.use {
                    assertSoftly(TexasAltinnTokenFetcher(texasHttpClient(it), it, "https://altinn.test").token("target").shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                        upstream shouldBe ALTINN_AUTHENTICATION
                        stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                        status shouldBe expectedStatus
                    }
                }
            }
        }

        listOf("not-a-jwt", JWT.create().sign(Algorithm.none())).forEachIndexed { index, body ->
            test("invalid token $index including missing expiry is a response failure with status") {
                upstreamHttpClient(respondWithSystemToken) { respondJson("\"$body\"") }.use {
                    assertSoftly(TexasAltinnTokenFetcher(texasHttpClient(it), it, "https://altinn.test").token("target").shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                        upstream shouldBe ALTINN_AUTHENTICATION
                        stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                        status shouldBe 200
                    }
                }
            }
        }

        listOf<Throwable>(IOException("Unavailable"), HttpRequestTimeoutException("https://altinn.test", 10)).forEach { cause ->
            test("exchange ${cause.javaClass.simpleName} becomes token exchange failure without status") {
                upstreamHttpClient(respondWithSystemToken) { throw cause }.use {
                    assertSoftly(TexasAltinnTokenFetcher(texasHttpClient(it), it, "https://altinn.test").token("target").shouldBeInstanceOf<UpstreamResult.Failure>().failure) {
                        upstream shouldBe ALTINN_AUTHENTICATION
                        stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                        status shouldBe null
                        this.cause?.javaClass shouldBe cause.javaClass
                    }
                }
            }
        }

        test("exchange cancellation is rethrown") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient(respondWithSystemToken) { throw cancelled }.use {
                shouldThrow<CancellationException> {
                    TexasAltinnTokenFetcher(texasHttpClient(it), it, "https://altinn.test").token("target")
                }.message shouldBe cancelled.message
            }
        }

        test("body cancellation is rethrown") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient(respondWithSystemToken) { respondJson("\"${altinnTestToken(3600)}\"") }.use {
                it.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (context.response.call.request.url.host == "altinn.test") throw cancelled
                }
                shouldThrow<CancellationException> {
                    TexasAltinnTokenFetcher(texasHttpClient(it), it, "https://altinn.test").token("target")
                } shouldBeSameInstanceAs cancelled
            }
        }
    })

internal fun altinnTestToken(secondsLeft: Long, now: Instant = Instant.now()): String = JWT.create()
    .withExpiresAt(now.plusSeconds(secondsLeft))
    .sign(Algorithm.none())
