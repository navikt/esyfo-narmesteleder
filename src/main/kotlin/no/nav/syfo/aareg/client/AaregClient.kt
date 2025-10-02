package no.nav.syfo.aareg.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import org.slf4j.LoggerFactory

private data class FinnArbeidsforholdoversikterPrArbeidstakerAPIRequest(
    val arbeidstakerId: String,
    val rapporteringsordninger: Set<Rapporteringsordning> = setOf(
        Rapporteringsordning.A_ORDNINGEN,
        Rapporteringsordning.FOER_A_ORDNINGEN
    ),
)

interface IAaregClient {
    suspend fun getArbeidsforhold(
        personIdent: String
    ): AaregArbeidsforholdOversikt
}

class AaregClientException(message: String, cause: Exception) : RuntimeException(message, cause)

class AaregClient(
    aaregBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String,
    private val httpClient: HttpClient = httpClientDefault()
) : IAaregClient {
    private val arbeidsforholdOversiktPath = "${aaregBaseUrl}$ARBEIDSFORHOLD_OVERSIKT_PATH"

    override suspend fun getArbeidsforhold(personIdent: String): AaregArbeidsforholdOversikt {
        return getArbeidsforholdInAareg(personIdent, getSystemToken())
    }

    private suspend fun getSystemToken() = runCatching {
        texasHttpClient.systemToken(
            TexasHttpClient.IDENTITY_PROVIDER_AZUREAD,
            TexasHttpClient.getTarget(scope)
        ).accessToken
    }.getOrElse {
        if (it is Exception) throw AaregClientException("Noe gikk galt ved henting av system-token", it)
        else throw it
    }

    private suspend fun getArbeidsforholdInAareg(
        personIdent: String,
        token: String
    ): AaregArbeidsforholdOversikt {
        val res = runCatching<AaregArbeidsforholdOversikt> {
            httpClient.post(arbeidsforholdOversiktPath) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(
                    FinnArbeidsforholdoversikterPrArbeidstakerAPIRequest(
                        arbeidstakerId = personIdent
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
        const val ARBEIDSFORHOLD_OVERSIKT_PATH = "/api/v2/arbeidstaker/arbeidsforholdoversikt"
        private val logger = LoggerFactory.getLogger(AaregClient::class.java)
    }
}
