package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.texas.altinnTestToken
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class AltinnAuthorizationClientTest :
    FunSpec({
        listOf(AuthorizationDecision.Permit, AuthorizationDecision.Deny, AuthorizationDecision.NotApplicable).forEach { decision ->
            test("returns $decision from the external PDP response") {
                upstreamHttpClient(respondWithSystemToken, { request ->
                    if (request.url.encodedPath.contains("exchange")) {
                        respondJson("\"${altinnTestToken(3600)}\"")
                    } else {
                        respondJson("""{"Response":[{"Decision":"$decision"}]}""")
                    }
                }).use {
                    client(it).authorize(System("synthetic-system-user"), setOf("910000001"), "resource") shouldBe UpstreamResult.Success(decision)
                }
            }
        }

        listOf(400, 503).forEach { status ->
            test("PDP HTTP $status is a response failure with status") {
                upstreamHttpClient(respondWithSystemToken, { request ->
                    if (request.url.encodedPath.contains("exchange")) {
                        respondJson("\"${altinnTestToken(3600)}\"")
                    } else {
                        respondJson("private-provider-body", HttpStatusCode.fromValue(status))
                    }
                }).use {
                    val failure = client(it).authorize(System("synthetic-system-user"), setOf("910000001"), "resource")
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                    failure.upstream shouldBe ALTINN_PDP
                    failure.stage shouldBe UpstreamFailureStage.RESPONSE
                    failure.status shouldBe status
                }
            }
        }

        listOf<Throwable>(IOException("Unavailable"), HttpRequestTimeoutException("https://altinn.test", 10)).forEach { cause ->
            test("PDP ${cause.javaClass.simpleName} is a request failure") {
                upstreamHttpClient(respondWithSystemToken, { request ->
                    if (request.url.encodedPath.contains("exchange")) {
                        respondJson("\"${altinnTestToken(3600)}\"")
                    } else {
                        throw cause
                    }
                }).use {
                    val failure = client(it).authorize(System("synthetic-system-user"), setOf("910000001"), "resource")
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                    failure.upstream shouldBe ALTINN_PDP
                    failure.stage shouldBe UpstreamFailureStage.REQUEST
                    failure.status shouldBe null
                    failure.cause?.javaClass shouldBe cause.javaClass
                }
            }
        }

        listOf(
            "malformed" to "not-json",
            "empty" to """{"Response":[]}""",
            "missing decision" to """{"Response":[{}]}""",
            "multiple including later error" to """{"Response":[{"Decision":"Permit"},{"Decision":"Indeterminate"}]}""",
            "multiple permits" to """{"Response":[{"Decision":"Permit"},{"Decision":"Permit"}]}""",
        ).forEach { (name, body) ->
            test("PDP $name fails closed with response status") {
                upstreamHttpClient(respondWithSystemToken, { request ->
                    if (request.url.encodedPath.contains("exchange")) {
                        respondJson("\"${altinnTestToken(3600)}\"")
                    } else {
                        respondJson(body)
                    }
                }).use {
                    val failure = client(it).authorize(System("synthetic-system-user"), setOf("910000001"), "resource")
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                    failure.upstream shouldBe ALTINN_PDP
                    failure.stage shouldBe UpstreamFailureStage.RESPONSE
                    failure.status shouldBe 200
                }
            }
        }

        listOf(
            "urn:oasis:names:tc:xacml:1.0:status:syntax-error",
            "urn:oasis:names:tc:xacml:1.0:status:processing-error",
            "private-status",
            null,
        ).forEach { statusCode ->
            test("Indeterminate with status $statusCode has only allowlisted detail and no cause") {
                upstreamHttpClient(respondWithSystemToken, { request ->
                    if (request.url.encodedPath.contains("exchange")) {
                        respondJson("\"${altinnTestToken(3600)}\"")
                    } else {
                        respondJson(
                            """{"Response":[{"Decision":"Indeterminate","Status":{"StatusMessage":"private-exception-message","StatusDetails":["private-details"],"StatusCode":{"Value":${statusCode?.let { "\"$it\"" }},"StatusCode":{"Value":"private-nested-code"}}}}]}""",
                        )
                    }
                }).use {
                    val failure = client(it).authorize(System("synthetic-system-user"), setOf("910000001"), "resource")
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                    failure.upstream shouldBe ALTINN_PDP
                    failure.stage shouldBe UpstreamFailureStage.RESPONSE
                    failure.status shouldBe 200
                    failure.cause shouldBe null
                    failure.errors shouldBe listOf(UpstreamError("indeterminate", detail = statusCode?.takeIf { it.startsWith("urn:") } ?: "unknown"))
                }
            }
        }

        listOf("texas", "altinn-authentication").forEach { upstream ->
            test("$upstream token failure is propagated unchanged without PDP request") {
                val failure = UpstreamFailure(UpstreamName(upstream), UpstreamFailureStage.TOKEN_EXCHANGE, 503, IOException())
                val provider = mockk<AltinnTokenProvider>()
                coEvery { provider.token(any()) } returns UpstreamResult.Failure(failure)
                upstreamHttpClient(respondWithSystemToken, { error("PDP must not be called") }).use {
                    client(it, provider).authorize(System("synthetic-system-user"), setOf("910000001"), "resource")
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure shouldBeSameInstanceAs failure
                }
            }
        }

        test("PDP cancellation is rethrown") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient(respondWithSystemToken, { request ->
                if (request.url.encodedPath.contains("exchange")) {
                    respondJson("\"${altinnTestToken(3600)}\"")
                } else {
                    throw cancelled
                }
            }).use {
                shouldThrow<CancellationException> {
                    client(it).authorize(System("synthetic-system-user"), setOf("910000001"), "resource")
                }.message shouldBe cancelled.message
            }
        }

        test("PDP body cancellation is rethrown") {
            val cancelled = CancellationException("Cancelled")
            upstreamHttpClient(respondWithSystemToken, { request ->
                if (request.url.encodedPath.contains("exchange")) {
                    respondJson("\"${altinnTestToken(3600)}\"")
                } else {
                    respondJson("""{"Response":[{"Decision":"Permit"}]}""")
                }
            }).use {
                it.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (subject.expectedType.type == AltinnAuthorizationResponse::class) throw cancelled
                }
                shouldThrow<CancellationException> {
                    client(it).authorize(System("synthetic-system-user"), setOf("910000001"), "resource")
                } shouldBeSameInstanceAs cancelled
            }
        }
    })

private fun client(httpClient: HttpClient, provider: AltinnTokenProvider = AltinnTokenProvider(texasHttpClient(httpClient), httpClient, "https://altinn.test")) = HttpAltinnAuthorizationClient(
    "https://altinn.test",
    httpClient,
    provider,
    "synthetic-subscription-key",
)
