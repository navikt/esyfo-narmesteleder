package no.nav.syfo.integration.pdl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpStatusCode
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

class PdlSinglePersonClientTest :
    FunSpec({
        test("full data without errors gives Success") {
            lookup { respondJson("""{"data":$FULL_DATA}""") }.shouldBeInstanceOf<UpstreamResult.Success<PdlPerson?>>().value shouldBe PdlPerson(
                PersonResponse(listOf(Navn("Test", null, "Person"))),
                listOf(Ident(SYNTHETIC_IDENT, Ident.GRUPPE_IDENT_FNR)),
            )
        }

        test("4xx gives a RESPONSE failure with status") {
            lookup { respondJson("{}", HttpStatusCode.BadRequest) }.failure().assertMetadata(UpstreamFailureStage.RESPONSE, 400)
        }

        test("5xx gives a RESPONSE failure with status") {
            lookup { respondJson("{}", HttpStatusCode.ServiceUnavailable) }.failure().assertMetadata(UpstreamFailureStage.RESPONSE, 503)
        }

        test("timeout gives a REQUEST failure") {
            lookup { throw SocketTimeoutException("private-canary") }.failure().assertMetadata(UpstreamFailureStage.REQUEST, null)
        }

        test("IO failure gives a REQUEST failure") {
            lookup { throw IOException("private-canary") }.failure().assertMetadata(UpstreamFailureStage.REQUEST, null)
        }

        test("GraphQL errors include all code detail and dot-joined path but never message") {
            val failure = lookup {
                respondJson(
                    """{"errors":[
                {"message":"private-canary $SYNTHETIC_IDENT","extensions":{"code":"bad_request","id":"missing_behandlingsnummer"},"path":["hentPerson","navn"]},
                {"message":"private-canary","extensions":{"code":"server_error"},"path":["hentIdenter"]},
                {"message":"private-canary"}
                ]}""",
                )
            }.failure()
            failure.assertMetadata(UpstreamFailureStage.RESPONSE, 200)
            failure.cause shouldBe null
            failure.errors shouldBe listOf(
                UpstreamError("bad_request", "missing_behandlingsnummer", "hentPerson.navn"),
                UpstreamError("server_error", path = "hentIdenter"),
                UpstreamError("unknown"),
            )
            failure.errors.toString() shouldNotContain "private-canary"
            failure.errors.toString() shouldNotContain SYNTHETIC_IDENT
        }

        test("not_found gives Success null") {
            lookup { respondJson("""{"errors":[{"extensions":{"code":"not_found"}}]}""") } shouldBe UpstreamResult.Success(null)
        }

        test("bad_request with ugyldig_ident gives Success null") {
            lookup { respondJson("""{"errors":[{"extensions":{"code":"bad_request","id":"ugyldig_ident"}}]}""") } shouldBe UpstreamResult.Success(null)
        }

        test("a mix of not_found and invalid ident gives Success null even with data") {
            lookup {
                respondJson("""{"data":$FULL_DATA,"errors":[{"extensions":{"code":"not_found"}},{"extensions":{"code":"bad_request","id":"ugyldig_ident"}}]}""")
            } shouldBe UpstreamResult.Success(null)
        }

        test("bad_request without ugyldig_ident gives Failure") {
            lookup { respondJson("""{"errors":[{"extensions":{"code":"bad_request"}}]}""") }.failure().errors shouldBe listOf(UpstreamError("bad_request"))
        }

        test("an error wins alongside full data and not_found") {
            lookup {
                respondJson("""{"data":$FULL_DATA,"errors":[{"extensions":{"code":"not_found"}},{"extensions":{"code":"unauthorized"}}]}""")
            }.failure().errors shouldBe listOf(UpstreamError("not_found"), UpstreamError("unauthorized"))
        }

        test("invalid JSON gives a RESPONSE failure with response status") {
            val failure = lookup { respondJson("{invalid-json") }.failure()
            failure.assertMetadata(UpstreamFailureStage.RESPONSE, 200)
            failure.cause?.javaClass?.simpleName shouldBe "JsonConvertException"
        }

        test("response without data gives incomplete_response") {
            lookup { respondJson("{}") }.failure().errors shouldBe listOf(UpstreamError("incomplete_response"))
        }

        test("response without person gives incomplete_response") {
            lookup { respondJson("""{"data":{"identer":{"identer":[]}}}""") }.failure().errors shouldBe listOf(UpstreamError("incomplete_response"))
        }

        test("response without identer gives incomplete_response") {
            lookup { respondJson("""{"data":{"person":{"navn":[]}}}""") }.failure().errors shouldBe listOf(UpstreamError("incomplete_response"))
        }

        test("token failure propagates TOKEN_EXCHANGE without requesting PDL") {
            upstreamHttpClient(
                token = { respondJson("""{"error":"invalid_target"}""", HttpStatusCode.BadRequest) },
                upstream = { error("PDL must not be requested") },
            ).use { http ->
                val failure = HttpPdlClient(http, "http://pdl", texasHttpClient(http), "api://pdl/.default")
                    .getPerson(SYNTHETIC_IDENT).failure()
                failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                failure.upstream.value shouldBe "texas"
            }
        }

        test("CancellationException during the request is rethrown") {
            shouldThrow<CancellationException> { lookup { throw CancellationException("cancelled") } }
        }

        test("CancellationException while decoding the body is rethrown") {
            val cancelled = CancellationException("cancelled")
            var receivingBody = false
            upstreamHttpClient(respondWithSystemToken) { respondJson("""{"data":$FULL_DATA}""") }.use { http ->
                http.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (subject.expectedType.type == GetPersonResponse::class) {
                        receivingBody = true
                        throw cancelled
                    }
                }
                shouldThrow<CancellationException> {
                    HttpPdlClient(http, "http://pdl", texasHttpClient(http), "api://pdl/.default").getPerson(SYNTHETIC_IDENT)
                } shouldBe cancelled
            }
            receivingBody shouldBe true
        }

        test("CancellationException during token exchange is rethrown") {
            upstreamHttpClient(
                token = { throw CancellationException("cancelled") },
                upstream = { error("PDL must not be requested") },
            ).use { http ->
                shouldThrow<CancellationException> {
                    HttpPdlClient(http, "http://pdl", texasHttpClient(http), "api://pdl/.default").getPerson(SYNTHETIC_IDENT)
                }
            }
        }
    })

private const val SYNTHETIC_IDENT = "12345678901"
private const val FULL_DATA = """{"person":{"navn":[{"fornavn":"Test","etternavn":"Person"}]},"identer":{"identer":[{"ident":"12345678901","gruppe":"FOLKEREGISTERIDENT"}]}}"""

private suspend fun lookup(upstream: MockRequestHandler): UpstreamResult<PdlPerson?> = upstreamHttpClient(respondWithSystemToken, upstream).use { http ->
    HttpPdlClient(http, "http://pdl", texasHttpClient(http), "api://pdl/.default").getPerson(SYNTHETIC_IDENT)
}

private fun UpstreamResult<PdlPerson?>.failure() = shouldBeInstanceOf<UpstreamResult.Failure>().failure

private fun UpstreamFailure.assertMetadata(expectedStage: UpstreamFailureStage, expectedStatus: Int?) {
    upstream shouldBe PDL
    stage shouldBe expectedStage
    status shouldBe expectedStatus
    toString() shouldNotContain SYNTHETIC_IDENT
    toString() shouldNotContain "private-canary"
}
