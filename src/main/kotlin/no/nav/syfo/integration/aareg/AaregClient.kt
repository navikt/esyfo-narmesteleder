package no.nav.syfo.integration.aareg

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import no.nav.syfo.application.api.NAV_CALL_ID_HEADER
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Mirrors AAREG's FinnArbeidsforholdoversikterPrArbeidstakerAPIRequest (v2).
 * Omitted filters use AAREG defaults; explicitly empty sets result in HTTP 400.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
private data class ArbeidsforholdoversiktRequest(
    val arbeidstakerId: String,
    val arbeidsforholdtyper: Set<Arbeidsforholdtype>? = null,
    val rapporteringsordninger: Set<Rapporteringsordning>? = null,
    val arbeidsforholdstatuser: Set<Arbeidsforholdstatus>? = null,
)

private enum class Arbeidsforholdtype {
    @JsonProperty("ordinaertArbeidsforhold")
    ORDINAERT_ARBEIDSFORHOLD,

    @JsonProperty("maritimtArbeidsforhold")
    MARITIMT_ARBEIDSFORHOLD,

    @JsonProperty("forenkletOppgjoersordning")
    FORENKLET_OPPGJOERSORDNING,
}

private enum class Arbeidsforholdstatus {
    AKTIV,
    FREMTIDIG,
    AVSLUTTET,
}

interface AaregClient {
    suspend fun getArbeidsforhold(
        personIdent: String
    ): AaregArbeidsforholdOversikt

    suspend fun getArbeidsforholdHistorikk(personIdent: String): AaregArbeidsforholdOversikt
}

class AaregClientException(
    message: String,
    cause: Exception,
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
        ArbeidsforholdoversiktRequest(
            arbeidstakerId = personIdent,
            rapporteringsordninger = setOf(Rapporteringsordning.A_ORDNINGEN, Rapporteringsordning.FOER_A_ORDNINGEN),
        )
    )

    override suspend fun getArbeidsforholdHistorikk(personIdent: String): AaregArbeidsforholdOversikt = try {
        fetchArbeidsforhold(
            ArbeidsforholdoversiktRequest(
                arbeidstakerId = personIdent,
                arbeidsforholdtyper = setOf(
                    Arbeidsforholdtype.ORDINAERT_ARBEIDSFORHOLD,
                    Arbeidsforholdtype.MARITIMT_ARBEIDSFORHOLD,
                    Arbeidsforholdtype.FORENKLET_OPPGJOERSORDNING,
                ),
                rapporteringsordninger = setOf(Rapporteringsordning.A_ORDNINGEN, Rapporteringsordning.FOER_A_ORDNINGEN),
                arbeidsforholdstatuser = setOf(Arbeidsforholdstatus.AKTIV, Arbeidsforholdstatus.FREMTIDIG, Arbeidsforholdstatus.AVSLUTTET),
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: AaregClientException) {
        throw e
    } catch (e: Exception) {
        throw AaregClientException("An error occurred when fetching employment history", cause = e)
    }

    private suspend fun fetchArbeidsforhold(request: ArbeidsforholdoversiktRequest): AaregArbeidsforholdOversikt {
        val callId = MDC.get("trace_id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val token = getSystemToken()
        return try {
            httpClient.post(arbeidsforholdOversiktPath) {
                bearerAuth(token)
                header(NAV_CALL_ID_HEADER, callId)
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
            throw AaregClientException(
                "$message (status=${e.response.status.value})",
                cause = e,
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
