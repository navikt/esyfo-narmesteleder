package no.nav.syfo.integration.dinesykmeldte

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import no.nav.syfo.texas.client.TexasHttpClient
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private data class GetIsActiveSykmeldingRequest(
    val sykmeldtFnr: String,
    val orgnummer: String
)

interface DinesykmeldteClient {
    suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean
}

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

    private suspend fun getSystemToken(): String = try {
        texasHttpClient.systemToken(
            TexasHttpClient.IDENTITY_PROVIDER_AZUREAD,
            TexasHttpClient.getTarget(scope)
        ).accessToken
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw DinesykmeldteClientException(
            "An error occurred when acquiring system token from ${TexasHttpClient.IDENTITY_PROVIDER_AZUREAD}",
            e
        )
    }

    companion object {
        const val DINESYKMELDTE_ACTIVE_SYKMELDING_PATH = "/api/sykmelding/isActiveSykmelding"
        private val logger = LoggerFactory.getLogger(HttpDinesykmeldteClient::class.java)
    }
}
