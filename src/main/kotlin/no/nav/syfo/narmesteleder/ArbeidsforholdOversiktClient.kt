package no.nav.syfo.narmesteleder

import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import no.nav.syfo.application.Environment
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import org.slf4j.LoggerFactory

enum class Rapporteringsordning {
    A_ORDNINGEN,
    FOER_A_ORDNINGEN
}

private data class FinnArbeidsforholdoversikterPrArbeidstakerAPIRequest(
    val arbeidstakerId: String,
    val rapporteringsordninger: Set<Rapporteringsordning> = setOf(
        Rapporteringsordning.A_ORDNINGEN,
        Rapporteringsordning.FOER_A_ORDNINGEN
    ),
)

class ArbeidsforholdOversiktClient(
    private val texasClient: TexasHttpClient,
    private val env: Environment // aareg
) {

    private val arbeidsforholdOversiktPath = "${"replace/with/env/var"}$ARBEIDSFORHOLD_OVERSIKT_PATH"

    private val httpClient = httpClientDefault()

    suspend fun getArbeidsforhold(personIdent: String): Unit =
        try {
            // TODO: Hvordan håndteres feil?
            // TODO: Target
            val token = texasClient.systemToken(TexasHttpClient.IDENTITY_PROVIDER_TOKENX, "target").accessToken

//            val token = azureAdClient.getSystemToken(urlEnv.aaregScope)?.accessToken
//                ?: throw RuntimeException("Failed to get Arbeidsforhold: No token was found")
//
            httpClient.post(arbeidsforholdOversiktPath) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
//                setBody(
//                    FinnArbeidsforholdoversikterPrArbeidstakerAPIRequest(
//                        arbeidstakerId = personIdent
//                    )
//                )
            }.body()
        } catch (e: ClientRequestException) {
//            if (e.response.status == HttpStatusCode.NotFound) {
//                logger.error("Fant ikke arbeidsforhold for bruker", e)
//                null
//            } else {
//                logger.error("Noe gikk galt ved henting av arbeidsforhold", e)
//                throw e
//            }
        } catch (e: ServerResponseException) {
            logger.error("Noe gikk galt ved henting av arbeidsforhold", e)
            throw e
        }

    companion object {
        const val ARBEIDSFORHOLD_OVERSIKT_PATH = "/api/v2/arbeidstaker/arbeidsforholdoversikt"
        private val logger = LoggerFactory.getLogger(ArbeidsforholdOversiktClient::class.java)
    }
}
