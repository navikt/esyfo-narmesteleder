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
import no.nav.syfo.aareg.client.AaregClient
import no.nav.syfo.aareg.client.AaregClientException
import no.nav.syfo.texas.client.TexasHttpClient
import org.slf4j.LoggerFactory

private data class GetIsActiveSykmeldingRequest(
    val fnr: String,
    val orgnummer: String
)

interface IDinesykmeldteClient {
    suspend fun getIsActiveSykmelding(fnr: String, orgnummer: String): Boolean
}

class DinesykmeldteException(message: String, cause: Exception) : RuntimeException(message, cause)

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
        val token = texasHttpClient.systemToken(
            "azuread",
            TexasHttpClient.getTarget(scope)
        ).accessToken
        // create body which contains fnr and orgnummer as Strings
        return true
    }
    private suspend fun getSystemToken() = runCatching {
        texasHttpClient.systemToken(
            TexasHttpClient.IDENTITY_PROVIDER_AZUREAD,
            TexasHttpClient.getTarget(scope)
        ).accessToken
    }.getOrElse {
        if (it is Exception) throw DinesykmeldteException("Noe gikk galt ved henting av system-token", it)
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
                        fnr = personIdent,
                        orgnummer = orgnummer
                    )
                )
            }.body()
        }

        return res.getOrElse { ex ->
            when (ex) {
                is ClientRequestException if ex.response.status == HttpStatusCode.NotFound -> {
                    throw AaregClientException("Error fetching arbeidsforhold oversikt for person $personIdent", ex)
                }

                is ClientRequestException -> {
                    throw AaregClientException(
                        "Noe gikk galt ved henting av arbeidsforhold",
                        ex
                    )
                }

                else -> throw ex
            }
        }
    }

    companion object {
        const val DINESYKMELDTE_ACTIVE_SYKMELDING_PATH = "/api/v2/arbeidstaker/arbeidsforholdoversikt"
        private val logger = LoggerFactory.getLogger(AaregClient::class.java)
    }
}