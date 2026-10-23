package no.nav.syfo.integration.ereg

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.syfo.platform.http.httpClientDefault
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.decodeUpstream
import no.nav.syfo.platform.upstream.requestUpstream

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
        val response = when (
            val result = requestUpstream(EREG) {
                httpClient.get("$eregBaseUrl/ereg/api/v2/organisasjon/$orgnummer") {
                    parameter("inkluderHierarki", true)
                    contentType(ContentType.Application.Json)
                }
            }
        ) {
            is UpstreamResult.Success -> result.value
            is UpstreamResult.Failure -> {
                if (result.failure.status == HttpStatusCode.NotFound.value) {
                    return UpstreamResult.Success(null)
                }
                return result
            }
        }
        return response.decodeUpstream(EREG) { body<Organisasjon>() }
    }
}
