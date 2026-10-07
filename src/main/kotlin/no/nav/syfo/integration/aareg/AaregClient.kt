package no.nav.syfo.integration.aareg

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
import kotlin.coroutines.cancellation.CancellationException

private data class FinnArbeidsforholdoversikterPrArbeidstakerAPIRequest(
    val arbeidstakerId: String,
    val rapporteringsordninger: Set<Rapporteringsordning> = setOf(
        Rapporteringsordning.A_ORDNINGEN,
        Rapporteringsordning.FOER_A_ORDNINGEN
    ),
)

interface AaregClient {
    suspend fun getArbeidsforhold(
        personIdent: String
    ): AaregArbeidsforholdOversikt
}

class AaregClientException(message: String, cause: Exception) : RuntimeException(message, cause)

class HttpAaregClient(
    aaregBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String,
    private val httpClient: HttpClient = httpClientDefault()
) : AaregClient {
    private val arbeidsforholdOversiktPath = "${aaregBaseUrl}$ARBEIDSFORHOLD_OVERSIKT_PATH"

    override suspend fun getArbeidsforhold(personIdent: String): AaregArbeidsforholdOversikt {
        val token = getSystemToken()
        return try {
            httpClient.post(arbeidsforholdOversiktPath) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(FinnArbeidsforholdoversikterPrArbeidstakerAPIRequest(arbeidstakerId = personIdent))
            }.body()
        } catch (e: ClientRequestException) {
            val message = if (e.response.status == HttpStatusCode.NotFound) {
                "Error fetching arbeidsforhold oversikt for person $personIdent"
            } else {
                "An error occurred when fetching arbeidsforhold"
            }
            throw AaregClientException(message, e)
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
        throw AaregClientException(
            "An error occurred when acquiring system token from ${TexasHttpClient.IDENTITY_PROVIDER_AZUREAD}",
            e
        )
    }

    companion object {
        const val ARBEIDSFORHOLD_OVERSIKT_PATH = "/api/v2/arbeidstaker/arbeidsforholdoversikt"
        private val logger = LoggerFactory.getLogger(HttpAaregClient::class.java)
    }
}
