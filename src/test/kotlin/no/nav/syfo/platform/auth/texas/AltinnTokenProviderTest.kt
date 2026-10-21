package no.nav.syfo.platform.auth.texas

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrThrow
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

class AltinnTokenProviderTest :
    FunSpec({
        test("Texas failure is propagated unchanged and skips Altinn") {
            val failure = UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 400, errors = listOf(UpstreamError("invalid_grant")))
            val texas = mockk<TexasHttpClient>()
            coEvery { texas.maskinportenSystemToken("target") } returns UpstreamResult.Failure(failure)
            upstreamHttpClient(respondWithSystemToken) { error("Altinn must not be called") }.use {
                AltinnTokenProvider(texas, it, "https://altinn.test").token("target")
                    .shouldBeInstanceOf<UpstreamResult.Failure>().failure shouldBeSameInstanceAs failure
            }
        }

        listOf(400, 503).forEach { status ->
            test("exchange HTTP $status retains status and token exchange stage") {
                upstreamHttpClient(respondWithSystemToken) { respondJson("private-response", HttpStatusCode.fromValue(status)) }.use {
                    val failure = AltinnTokenProvider(texasHttpClient(it), it, "https://altinn.test").token("target")
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                    failure.upstream shouldBe ALTINN_AUTHENTICATION
                    failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    failure.status shouldBe status
                }
            }
        }

        listOf("not-a-jwt", JWT.create().sign(Algorithm.none())).forEachIndexed { index, body ->
            test("invalid token $index including missing expiry is a response failure with status") {
                upstreamHttpClient(respondWithSystemToken) { respondJson("\"$body\"") }.use {
                    val failure = AltinnTokenProvider(texasHttpClient(it), it, "https://altinn.test").token("target")
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                    failure.upstream shouldBe ALTINN_AUTHENTICATION
                    failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    failure.status shouldBe 200
                }
            }
        }

        listOf<Throwable>(IOException("Unavailable"), HttpRequestTimeoutException("https://altinn.test", 10)).forEach { cause ->
            test("exchange ${cause.javaClass.simpleName} becomes token exchange failure without status") {
                upstreamHttpClient(respondWithSystemToken) { throw cause }.use {
                    val failure = AltinnTokenProvider(texasHttpClient(it), it, "https://altinn.test").token("target")
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                    failure.upstream shouldBe ALTINN_AUTHENTICATION
                    failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                    failure.status shouldBe null
                    failure.cause?.javaClass shouldBe cause.javaClass
                }
            }
        }

        test("exchange cancellation is rethrown") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient(respondWithSystemToken, { throw cancelled }).use {
                shouldThrow<CancellationException> {
                    AltinnTokenProvider(texasHttpClient(it), it, "https://altinn.test").token("target")
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
                    AltinnTokenProvider(texasHttpClient(it), it, "https://altinn.test").token("target")
                } shouldBeSameInstanceAs cancelled
            }
        }

        listOf(7200L, 301L, 300L, 200L, 60L, 0L).forEach { secondsLeft ->
            test("cache at $secondsLeft seconds uses only tokens above 300 seconds then caches the renewed token") {
                var texasCalls = 0
                var exchanges = 0
                val now = Instant.parse("2026-10-15T06:00:00Z")
                val clock = Clock.fixed(now, ZoneOffset.UTC)
                val initial = altinnTestToken(secondsLeft, now)
                val renewed = altinnTestToken(10800, now)
                val tokenHandler: MockRequestHandler = { request ->
                    texasCalls++
                    respondWithSystemToken(request)
                }
                upstreamHttpClient(tokenHandler, { request ->
                    request.url.encodedPath shouldBe "/authentication/api/v1/exchange/maskinporten"
                    exchanges++
                    respondJson("\"${if (exchanges == 1) initial else renewed}\"")
                }).use {
                    val provider = AltinnTokenProvider(texasHttpClient(it), it, "https://altinn.test", clock)
                    provider.token("target").getOrThrow().accessToken shouldBe initial
                    val expected = if (secondsLeft > 300) initial else renewed
                    provider.token("target").getOrThrow().accessToken shouldBe expected
                    provider.token("target").getOrThrow().accessToken shouldBe expected
                    texasCalls shouldBe if (secondsLeft > 300) 1 else 2
                    exchanges shouldBe texasCalls
                    provider.token("other-target").getOrThrow().accessToken shouldBe renewed
                    exchanges shouldBe texasCalls
                    exchanges shouldBe if (secondsLeft > 300) 2 else 3
                }
            }
        }

        test("failed exchange is not cached") {
            var exchanges = 0
            val token = altinnTestToken(3600)
            upstreamHttpClient(respondWithSystemToken, {
                exchanges++
                if (exchanges == 1) respondJson("invalid") else respondJson("\"$token\"")
            }).use {
                val provider = AltinnTokenProvider(texasHttpClient(it), it, "https://altinn.test")
                provider.token("target").shouldBeInstanceOf<UpstreamResult.Failure>()
                provider.token("target").getOrThrow().accessToken shouldBe token
                provider.token("target").getOrThrow().accessToken shouldBe token
                exchanges shouldBe 2
            }
        }
    })

internal fun altinnTestToken(secondsLeft: Long, now: Instant = Instant.now()): String = JWT.create()
    .withExpiresAt(now.plusSeconds(secondsLeft))
    .sign(Algorithm.none())
