package no.nav.syfo.dinesykmeldte.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.syfo.aareg.client.AaregClientException
import no.nav.syfo.texas.client.TexasHttpClient
import org.slf4j.LoggerFactory

private data class GetIsActiveSykmeldingRequest(
    val sykmeldtFnr: String,
    val orgnummer: String
)

interface IDinesykmeldteClient {
    suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean
}

class DinesykmeldteClientException(message: String, cause: Exception) : RuntimeException(message, cause)

class DinesykmeldteClient(
    private val httpClient: HttpClient,
    dinesykmeldteBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String
): IDinesykmeldteClient {
    private val arbeidsforholdOversiktPath = "${dinesykmeldteBaseUrl}$DINESYKMELDTE_ACTIVE_SYKMELDING_PATH"

    override suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean {
        return getDineSykmeldteIsActiveSykmelding(
            fnr,
            orgnummer,
            getSystemToken()
        )
    }
    private suspend fun getSystemToken() = runCatching {
        texasHttpClient.systemToken(
            TexasHttpClient.IDENTITY_PROVIDER_AZUREAD,
            TexasHttpClient.getTarget(scope)
        ).accessToken
    }.getOrElse {
        if (it is Exception) throw DinesykmeldteClientException("An error occurred when acquiring system token from ${TexasHttpClient.IDENTITY_PROVIDER_AZUREAD}", it)
        else throw it
    }

    private suspend fun getDineSykmeldteIsActiveSykmelding(
        personIdent: String,
        orgnummer: String,
        token: String
    ): Boolean {
        val res = runCatching<Boolean> {
            httpClient.post(arbeidsforholdOversiktPath) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(
                    GetIsActiveSykmeldingRequest(
                        sykmeldtFnr = personIdent,
                        orgnummer = orgnummer
                    )
                )
            }.body()
        }

        return res.getOrElse { ex ->
            when (ex) {
                is ClientRequestException if ex.response.status == HttpStatusCode.NotFound -> {
                    throw AaregClientException("Error when fetching sick leave status for person $personIdent", ex)
                }

                is ClientRequestException -> {
                    throw DinesykmeldteClientException(
                        "An error occurred when fetching sick leave status",
                        ex
                    )
                }

                else -> throw ex
            }
        }
    }

    companion object {
        const val DINESYKMELDTE_ACTIVE_SYKMELDING_PATH = "/api/sykmelding/isActiveSykmelding"
        private val logger = LoggerFactory.getLogger(DinesykmeldteClient::class.java)
    }
}
