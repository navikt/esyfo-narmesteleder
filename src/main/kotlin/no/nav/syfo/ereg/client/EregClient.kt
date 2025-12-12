package no.nav.syfo.ereg.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.util.httpClientDefault
import no.nav.syfo.util.logger

interface IEaregClient {
    suspend fun getOrganisasjon(
        orgnummer: String
    ): Organisasjon?
}

class EregClient(
    val eregBaseUrl: String,
    private val httpClient: HttpClient = httpClientDefault()
) : IEaregClient {
    override suspend fun getOrganisasjon(orgnummer: String): Organisasjon? {
        val response = try {
            val response = httpClient.get("$eregBaseUrl/ereg/api/v1/organisasjon/$orgnummer") {
                parameter("inkluderHierarki", true)
                contentType(ContentType.Application.Json)
            }.body<Organisasjon>()
            response
        } catch (e: ResponseException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                logger.error("Could not find organization for orgNumber $orgnummer")
                null
            } else throw UpstreamRequestException("Error when fetching organization from ereg", e)
        }
        return response
    }

    companion object {
        private val logger = logger()
    }
}
