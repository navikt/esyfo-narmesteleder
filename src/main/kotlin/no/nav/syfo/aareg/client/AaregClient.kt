package no.nav.syfo.aareg.client

import io.ktor.client.HttpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.plugins.NotFoundException
import no.nav.syfo.application.exception.ApiErrorException
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
    }.getOrElse { ex ->
        when (ex) {
            is ClientRequestException ->
                throw ApiErrorException.InternalServerErrorException("Noe gikk galt ved henting av system-token")

            else -> throw ex
        }
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
                    throw NotFoundException("Fant ingen arbeidsforhold for bruker")
                }

                is ClientRequestException -> {
                    throw ApiErrorException.InternalServerErrorException(
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
