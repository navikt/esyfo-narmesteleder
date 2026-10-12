package no.nav.syfo.integration.ereg

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.util.httpClientDefault
import kotlin.coroutines.cancellation.CancellationException

internal val EREG = UpstreamName("ereg")

interface EregClient {
    suspend fun getOrganisasjon(
        orgnummer: String
    ): UpstreamResult<Organisasjon?>
}

class HttpEregClient(
    val eregBaseUrl: String,
    private val httpClient: HttpClient = httpClientDefault()
) : EregClient {
    override suspend fun getOrganisasjon(orgnummer: String): UpstreamResult<Organisasjon?> {
        val response = try {
            httpClient.get("$eregBaseUrl/ereg/api/v2/organisasjon/$orgnummer") {
                parameter("inkluderHierarki", true)
                contentType(ContentType.Application.Json)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                return UpstreamResult.Success(null)
            }
            return UpstreamResult.Failure(UpstreamFailure(EREG, UpstreamFailureStage.RESPONSE, e.response.status.value, e))
        } catch (e: Exception) {
            return UpstreamResult.Failure(UpstreamFailure(EREG, UpstreamFailureStage.REQUEST, null, e))
        }
        return try {
            UpstreamResult.Success(response.body<Organisasjon>())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpstreamResult.Failure(UpstreamFailure(EREG, UpstreamFailureStage.RESPONSE, response.status.value, e))
        }
    }
}
