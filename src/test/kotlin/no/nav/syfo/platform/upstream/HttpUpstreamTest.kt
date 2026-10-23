package no.nav.syfo.platform.upstream

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import no.nav.syfo.integration.httpClientDefaultWithoutRetryDelay
import no.nav.syfo.integration.respondJson

private val expectedUpstream = UpstreamName("test-upstream")

class HttpUpstreamTest :
    FunSpec({
        test("requestUpstream returns a successful response") {
            mockHttpClient().use { client ->
                requestUpstream(expectedUpstream) { client.get("https://upstream.test") }
                    .shouldBeInstanceOf<UpstreamResult.Success<HttpResponse>>()
                    .value.status shouldBe HttpStatusCode.OK
            }
        }

        test("requestUpstream classifies HTTP failures as response failures") {
            mockHttpClient(status = HttpStatusCode.BadRequest).use { client ->
                assertSoftly(
                    requestUpstream(expectedUpstream) { client.get("https://upstream.test") }
                        .shouldBeInstanceOf<UpstreamResult.Failure>()
                        .failure,
                ) {
                    upstream shouldBe expectedUpstream
                    stage shouldBe UpstreamFailureStage.RESPONSE
                    status shouldBe HttpStatusCode.BadRequest.value
                }
            }
        }

        test("requestUpstream classifies other exceptions as request failures") {
            val cause = IllegalStateException("request failed")

            requestUpstream(expectedUpstream) { throw cause }
                .shouldBeInstanceOf<UpstreamResult.Failure>()
                .failure shouldBe UpstreamFailure(expectedUpstream, UpstreamFailureStage.REQUEST, null, cause)
        }

        test("requestUpstream rethrows cancellation") {
            shouldThrow<CancellationException> {
                requestUpstream(expectedUpstream) { throw CancellationException("cancelled") }
            }
        }

        test("decodeUpstream returns a decoded value") {
            mockHttpClient().use { client ->
                client.get("https://upstream.test")
                    .decodeUpstream(expectedUpstream) { status.value }
                    .shouldBeInstanceOf<UpstreamResult.Success<Int>>()
                    .value shouldBe HttpStatusCode.OK.value
            }
        }

        test("decodeUpstream classifies decoding exceptions as response failures") {
            mockHttpClient().use { client ->
                val cause = IllegalArgumentException("invalid response")

                client.get("https://upstream.test")
                    .decodeUpstream<Unit>(expectedUpstream) { throw cause }
                    .shouldBeInstanceOf<UpstreamResult.Failure>()
                    .failure shouldBe UpstreamFailure(
                        expectedUpstream,
                        UpstreamFailureStage.RESPONSE,
                        HttpStatusCode.OK.value,
                        cause,
                    )
            }
        }

        test("decodeUpstream rethrows cancellation") {
            mockHttpClient().use { client ->
                shouldThrow<CancellationException> {
                    client.get("https://upstream.test")
                        .decodeUpstream<Unit>(expectedUpstream) { throw CancellationException("cancelled") }
                }
            }
        }
    })

private fun mockHttpClient(status: HttpStatusCode = HttpStatusCode.OK): HttpClient = httpClientDefaultWithoutRetryDelay(
    MockEngine {
        respondJson("{}", status)
    },
)
