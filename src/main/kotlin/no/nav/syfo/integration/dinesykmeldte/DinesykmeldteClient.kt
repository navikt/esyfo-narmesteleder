package no.nav.syfo.integration.dinesykmeldte

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrElse
import kotlin.coroutines.cancellation.CancellationException

private data class GetIsActiveSykmeldingRequest(
    val sykmeldtFnr: String,
    val orgnummer: String
)

interface DinesykmeldteClient {
    suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): UpstreamResult<Boolean>
}

internal val DINESYKMELDTE = UpstreamName("dinesykmeldte")

class HttpDinesykmeldteClient(
    private val httpClient: HttpClient,
    dinesykmeldteBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String
) : DinesykmeldteClient {
    private val isActiveSykmeldingPath = "${dinesykmeldteBaseUrl}$DINESYKMELDTE_ACTIVE_SYKMELDING_PATH"

    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): UpstreamResult<Boolean> {
        val token = texasHttpClient.azureAdSystemToken(scope).getOrElse { return UpstreamResult.Failure(it) }
        val response = try {
            httpClient.post(isActiveSykmeldingPath) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(GetIsActiveSykmeldingRequest(sykmeldtFnr = fnr, orgnummer = orgnummer))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return failure(UpstreamFailureStage.RESPONSE, e.response.status.value, e)
        } catch (e: Exception) {
            return failure(UpstreamFailureStage.REQUEST, null, e)
        }

        return response.bodyResult()
    }

    private suspend fun HttpResponse.bodyResult(): UpstreamResult<Boolean> = try {
        UpstreamResult.Success(body<Boolean>())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(UpstreamFailureStage.RESPONSE, status.value, e)
    }

    private fun failure(stage: UpstreamFailureStage, status: Int?, cause: Throwable) = UpstreamResult.Failure(
        UpstreamFailure(DINESYKMELDTE, stage, status, cause),
    )

    companion object {
        const val DINESYKMELDTE_ACTIVE_SYKMELDING_PATH = "/api/sykmelding/isActiveSykmelding"
    }
}
