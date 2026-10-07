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

private data class FinnArbeidsforholdhistorikkPrArbeidstakerAPIRequest(
    val arbeidstakerId: String,
    val arbeidsforholdtyper: Set<String> = setOf(
        "ordinaertArbeidsforhold",
        "maritimtArbeidsforhold",
        "forenkletOppgjoersordning",
    ),
    val rapporteringsordninger: Set<Rapporteringsordning> = setOf(
        Rapporteringsordning.A_ORDNINGEN,
        Rapporteringsordning.FOER_A_ORDNINGEN,
    ),
    val arbeidsforholdstatuser: Set<String> = setOf("AKTIV", "FREMTIDIG", "AVSLUTTET"),
)

interface AaregClient {
    suspend fun getArbeidsforhold(
        personIdent: String
    ): AaregArbeidsforholdOversikt

    suspend fun getArbeidsforholdHistorikk(personIdent: String): AaregArbeidsforholdOversikt
}

class AaregClientException(
    message: String,
    cause: Exception? = null,
    val reason: Reason = Reason.UNAVAILABLE,
) : RuntimeException(message, cause) {
    enum class Reason {
        PERSON_NOT_FOUND,
        UNAVAILABLE,
    }
}

class HttpAaregClient(
    aaregBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String,
    private val httpClient: HttpClient = httpClientDefault()
) : AaregClient {
    private val arbeidsforholdOversiktPath = "${aaregBaseUrl}$ARBEIDSFORHOLD_OVERSIKT_PATH"

    override suspend fun getArbeidsforhold(personIdent: String): AaregArbeidsforholdOversikt = fetchArbeidsforhold(
        FinnArbeidsforholdoversikterPrArbeidstakerAPIRequest(arbeidstakerId = personIdent)
    )

    override suspend fun getArbeidsforholdHistorikk(personIdent: String): AaregArbeidsforholdOversikt = try {
        fetchArbeidsforhold(FinnArbeidsforholdhistorikkPrArbeidstakerAPIRequest(arbeidstakerId = personIdent))
    } catch (e: CancellationException) {
        throw e
    } catch (e: AaregClientException) {
        throw e
    } catch (e: Exception) {
        throw AaregClientException("An error occurred when fetching employment history (${e.javaClass.simpleName})")
    }

    private suspend fun fetchArbeidsforhold(request: Any): AaregArbeidsforholdOversikt {
        val token = getSystemToken()
        return try {
            httpClient.post(arbeidsforholdOversiktPath) {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        } catch (e: ClientRequestException) {
            val notFound = e.response.status == HttpStatusCode.NotFound
            val message = if (notFound) {
                "Person not found when fetching arbeidsforhold"
            } else {
                "An error occurred when fetching arbeidsforhold"
            }
            // Ktor exceptions can retain personal data from the upstream response body.
            throw AaregClientException(
                "$message (status=${e.response.status.value})",
                reason = if (notFound) AaregClientException.Reason.PERSON_NOT_FOUND else AaregClientException.Reason.UNAVAILABLE,
            )
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
            cause = e,
        )
    }

    companion object {
        const val ARBEIDSFORHOLD_OVERSIKT_PATH = "/api/v2/arbeidstaker/arbeidsforholdoversikt"
        private val logger = LoggerFactory.getLogger(HttpAaregClient::class.java)
    }
}
