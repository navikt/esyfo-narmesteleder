package no.nav.syfo.integration.dinesykmeldte

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import org.slf4j.LoggerFactory

private data class GetIsActiveSykmeldingRequest(
    val sykmeldtFnr: String,
    val orgnummer: String
)

interface DinesykmeldteClient {
    suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean
}

internal val DINESYKMELDTE = UpstreamName("dinesykmeldte-backend")

class DinesykmeldteClientException(message: String, cause: Exception) : RuntimeException(message, cause)

class HttpDinesykmeldteClient(
    private val httpClient: HttpClient,
    dinesykmeldteBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String
) : DinesykmeldteClient {
    private val isActiveSykmeldingPath = "${dinesykmeldteBaseUrl}$DINESYKMELDTE_ACTIVE_SYKMELDING_PATH"

    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean {
        val token = getSystemToken()
        return try {
            httpClient.post(isActiveSykmeldingPath) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(GetIsActiveSykmeldingRequest(sykmeldtFnr = fnr, orgnummer = orgnummer))
            }.body()
        } catch (e: ClientRequestException) {
            throw DinesykmeldteClientException("An error occurred when fetching sick leave status", e)
        }
    }

    private suspend fun getSystemToken(): String = when (val result = texasHttpClient.azureAdSystemToken(scope, DINESYKMELDTE)) {
        is UpstreamResult.Success -> result.value
        is UpstreamResult.Failure -> throw DinesykmeldteClientException(
            "An error occurred when acquiring system token from ${TexasHttpClient.IDENTITY_PROVIDER_AZUREAD}",
            result.failure.cause as? Exception ?: IllegalStateException("Token exchange failed"),
        )
    }

    companion object {
        const val DINESYKMELDTE_ACTIVE_SYKMELDING_PATH = "/api/sykmelding/isActiveSykmelding"
        private val logger = LoggerFactory.getLogger(HttpDinesykmeldteClient::class.java)
    }
}
