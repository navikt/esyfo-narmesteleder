package no.nav.syfo.integration.pdl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import no.nav.syfo.integration.TEST_SYSTEM_TOKEN
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

class PdlPersonBolkClientTest :
    FunSpec({
        test("success requests a system token on every call") {
            var tokenCalls = 0
            var pdlCalls = 0
            upstreamHttpClient(
                token = {
                    tokenCalls++
                    respondJson("""{"access_token":"$TEST_SYSTEM_TOKEN","expires_in":3600,"token_type":"Bearer"}""")
                },
                upstream = { request ->
                    pdlCalls++
                    request.headers[HttpHeaders.Authorization] shouldBe "Bearer $TEST_SYSTEM_TOKEN"
                    request.headers["behandlingsnummer"] shouldBe "B506"
                    respondJson("""{"data":$BATCH_DATA}""")
                },
            ).use { http ->
                val client = HttpPdlClient(http, "http://pdl", texasHttpClient(http), "pdl")
                repeat(2) {
                    val response = client.getPersonBolk(SYNTHETIC_IDENTS).shouldBeInstanceOf<UpstreamResult.Success<GetPersonBolkResponse>>().value
                    response.data?.hentPersonBolk shouldBe listOf(
                        HentPersonBolk(SYNTHETIC_IDENTS[0], Person(listOf(Navn("Test", null, "Person"))), "ok"),
                        HentPersonBolk(SYNTHETIC_IDENTS[1], null, "bad_request"),
                    )
                }
            }
            tokenCalls shouldBe 2
            pdlCalls shouldBe 2
        }

        test("hentPersonBolk present with top-level errors gives Success") {
            val response = batchLookup {
                respondJson("""{"data":$BATCH_DATA,"errors":[{"extensions":{"code":"server_error"}}]}""")
            }.shouldBeInstanceOf<UpstreamResult.Success<GetPersonBolkResponse>>().value
            response.data?.hentPersonBolk?.size shouldBe 2
            response.errors?.single()?.extensions?.code shouldBe "server_error"
        }

        test("missing hentPersonBolk carries all errors with messages only for PDL-specific errors") {
            val failure = batchLookup {
                respondJson(
                    """{"data":{"hentIdenterBolk":[]},"errors":[
                    {"message":"Mangler behandlingsnummer","extensions":{"code":"bad_request","id":"missing_behandlingsnummer"},"path":["hentPersonBolk","navn"]},
                    {"message":"Ukjent problem oppsto","extensions":{"code":"server_error"},"path":["hentIdenterBolk"]},
                    {"message":"private-canary ${SYNTHETIC_IDENTS.joinToString()}"}
                    ]}""",
                )
            }.batchFailure()
            failure.assertBatchMetadata(UpstreamFailureStage.RESPONSE, 200)
            failure.cause shouldBe null
            failure.errors shouldBe listOf(
                UpstreamError("bad_request", "missing_behandlingsnummer", "hentPersonBolk.navn", "Mangler behandlingsnummer"),
                UpstreamError("server_error", path = "hentIdenterBolk", message = "Ukjent problem oppsto"),
                UpstreamError("unknown"),
            )
        }

        test("missing hentPersonBolk without errors gives incomplete_response") {
            val failure = batchLookup { respondJson("""{"data":{"hentIdenterBolk":[]}}""") }.batchFailure()
            failure.assertBatchMetadata(UpstreamFailureStage.RESPONSE, 200)
            failure.errors shouldBe listOf(UpstreamError("incomplete_response"))
        }

        test("missing data gives incomplete_response") {
            batchLookup { respondJson("{}") }.batchFailure().errors shouldBe listOf(UpstreamError("incomplete_response"))
        }

        test("4xx gives a RESPONSE failure with status") {
            batchLookup { respondJson("private-canary", HttpStatusCode.BadRequest) }.batchFailure()
                .assertBatchMetadata(UpstreamFailureStage.RESPONSE, 400)
        }

        test("5xx gives a RESPONSE failure with status") {
            batchLookup { respondJson("private-canary", HttpStatusCode.ServiceUnavailable) }.batchFailure()
                .assertBatchMetadata(UpstreamFailureStage.RESPONSE, 503)
        }

        test("timeout gives a REQUEST failure") {
            batchLookup { throw SocketTimeoutException("private-canary") }.batchFailure()
                .assertBatchMetadata(UpstreamFailureStage.REQUEST, null)
        }

        test("IO failure gives a REQUEST failure") {
            batchLookup { throw IOException("private-canary") }.batchFailure()
                .assertBatchMetadata(UpstreamFailureStage.REQUEST, null)
        }

        test("invalid JSON gives a RESPONSE failure with response status") {
            val failure = batchLookup { respondJson("{invalid-json private-canary") }.batchFailure()
            failure.assertBatchMetadata(UpstreamFailureStage.RESPONSE, 200)
            failure.cause?.javaClass?.simpleName shouldBe "JsonConvertException"
        }

        test("token failure propagates TOKEN_EXCHANGE without requesting PDL") {
            upstreamHttpClient(
                token = { respondJson("""{"error":"invalid_target"}""", HttpStatusCode.BadRequest) },
                upstream = { error("PDL must not be requested") },
            ).use { http ->
                val failure = HttpPdlClient(http, "http://pdl", texasHttpClient(http), "pdl")
                    .getPersonBolk(SYNTHETIC_IDENTS).batchFailure()
                failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
                failure.upstream.value shouldBe "texas"
                failure.status shouldBe 400
            }
        }

        test("CancellationException during the request is rethrown") {
            val cancelled = CancellationException("cancelled")
            shouldThrow<CancellationException> { batchLookup { throw cancelled } } shouldBe cancelled
        }

        test("CancellationException while decoding the body is rethrown") {
            val cancelled = CancellationException("cancelled")
            var receivingBody = false
            upstreamHttpClient(respondWithSystemToken) { respondJson("""{"data":$BATCH_DATA}""") }.use { http ->
                http.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                    if (subject.expectedType.type == GetPersonBolkResponse::class) {
                        receivingBody = true
                        throw cancelled
                    }
                }
                shouldThrow<CancellationException> {
                    HttpPdlClient(http, "http://pdl", texasHttpClient(http), "pdl").getPersonBolk(SYNTHETIC_IDENTS)
                } shouldBe cancelled
            }
            receivingBody shouldBe true
        }

        test("CancellationException during token exchange is rethrown") {
            val cancelled = CancellationException("cancelled")
            upstreamHttpClient(
                token = { throw cancelled },
                upstream = { error("PDL must not be requested") },
            ).use { http ->
                shouldThrow<CancellationException> {
                    HttpPdlClient(http, "http://pdl", texasHttpClient(http), "pdl").getPersonBolk(SYNTHETIC_IDENTS)
                } shouldBe cancelled
            }
        }
    })

private val SYNTHETIC_IDENTS = listOf("90000000001", "90000000002")
private const val BATCH_DATA =
    """{"hentPersonBolk":[{"ident":"90000000001","person":{"navn":[{"fornavn":"Test","etternavn":"Person"}]},"code":"ok"},{"ident":"90000000002","person":null,"code":"bad_request"}],"hentIdenterBolk":[]}"""

private suspend fun batchLookup(upstream: MockRequestHandler): UpstreamResult<GetPersonBolkResponse> = upstreamHttpClient(respondWithSystemToken, upstream).use { http ->
    HttpPdlClient(http, "http://pdl", texasHttpClient(http), "pdl").getPersonBolk(SYNTHETIC_IDENTS)
}

private fun UpstreamResult<GetPersonBolkResponse>.batchFailure() = shouldBeInstanceOf<UpstreamResult.Failure>().failure

private fun UpstreamFailure.assertBatchMetadata(expectedStage: UpstreamFailureStage, expectedStatus: Int?) {
    upstream shouldBe PDL
    stage shouldBe expectedStage
    status shouldBe expectedStatus
    SYNTHETIC_IDENTS.forEach {
        toString() shouldNotContain it
        errors.toString() shouldNotContain it
    }
    toString() shouldNotContain "private-canary"
    errors.toString() shouldNotContain "private-canary"
}
