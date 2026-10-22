package no.nav.syfo.integration.dinesykmeldte

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import no.nav.syfo.integration.TEST_SYSTEM_TOKEN
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.texasHttpClient
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import kotlin.coroutines.cancellation.CancellationException

class DinesykmeldteClientTest :
    FunSpec({
        val fnr = "12345678910"
        val orgnummer = "123456789"

        test("returns the active sykmelding status from dinesykmeldte with a system token") {
            var authorization: String? = null
            var path: String? = null
            val client = dinesykmeldteClient(
                dinesykmeldte = { request ->
                    authorization = request.headers[HttpHeaders.Authorization]
                    path = request.url.encodedPath
                    respondJson("true")
                }
            )

            client.getIsActiveSykmelding(fnr, orgnummer) shouldBe UpstreamResult.Success(true)
            authorization shouldBe "Bearer $TEST_SYSTEM_TOKEN"
            path shouldBe HttpDinesykmeldteClient.DINESYKMELDTE_ACTIVE_SYKMELDING_PATH
        }

        test("returns a RESPONSE failure for 4xx responses") {
            val client = dinesykmeldteClient(dinesykmeldte = { respondJson("", HttpStatusCode.BadRequest) })

            val failure = client.getIsActiveSykmelding(fnr, orgnummer).shouldBeInstanceOf<UpstreamResult.Failure>().failure
            failure.upstream shouldBe DINESYKMELDTE
            failure.stage shouldBe UpstreamFailureStage.RESPONSE
            failure.status shouldBe 400
        }

        test("returns the Texas failure when acquiring the system token fails") {
            val client = dinesykmeldteClient(
                token = { respondJson("", HttpStatusCode.InternalServerError) },
                dinesykmeldte = { respondJson("true") },
            )

            val failure = client.getIsActiveSykmelding(fnr, orgnummer).shouldBeInstanceOf<UpstreamResult.Failure>().failure
            failure.upstream.value shouldBe "texas"
            failure.stage shouldBe UpstreamFailureStage.TOKEN_EXCHANGE
        }

        test("propagates cancellation while acquiring system token") {
            val client = dinesykmeldteClient(
                token = { throw CancellationException("cancelled") },
                dinesykmeldte = { respondJson("true") },
            )

            shouldThrow<CancellationException> {
                client.getIsActiveSykmelding(fnr, orgnummer)
            }
        }
    })

private fun dinesykmeldteClient(
    token: MockRequestHandler = respondWithSystemToken,
    dinesykmeldte: MockRequestHandler,
): HttpDinesykmeldteClient {
    val httpClient = upstreamHttpClient(token = token, upstream = dinesykmeldte)
    return HttpDinesykmeldteClient(
        httpClient = httpClient,
        dinesykmeldteBaseUrl = "http://dinesykmeldte",
        texasHttpClient = texasHttpClient(httpClient),
        scope = "scope",
    )
}
