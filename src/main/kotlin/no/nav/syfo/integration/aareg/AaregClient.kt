package no.nav.syfo.integration.aareg

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import no.nav.syfo.application.api.NAV_CALL_ID_HEADER
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.platform.upstream.getOrElse
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import org.slf4j.MDC
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

internal val AAREG = UpstreamName("aareg")

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
    ): UpstreamResult<AaregArbeidsforholdOversikt>

    suspend fun getArbeidsforholdHistorikk(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt>
}

class HttpAaregClient(
    aaregBaseUrl: String,
    private val texasHttpClient: TexasHttpClient,
    private val scope: String,
    private val httpClient: HttpClient = httpClientDefault(),
) : AaregClient {
    private val arbeidsforholdOversiktPath = "${aaregBaseUrl}$ARBEIDSFORHOLD_OVERSIKT_PATH"

    override suspend fun getArbeidsforhold(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> = fetchArbeidsforhold(
        ArbeidsforholdoversiktRequest(
            arbeidstakerId = personIdent,
            rapporteringsordninger = setOf(Rapporteringsordning.A_ORDNINGEN, Rapporteringsordning.FOER_A_ORDNINGEN),
        )
    )

    override suspend fun getArbeidsforholdHistorikk(personIdent: String): UpstreamResult<AaregArbeidsforholdOversikt> = fetchArbeidsforhold(
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

    private suspend fun fetchArbeidsforhold(request: ArbeidsforholdoversiktRequest): UpstreamResult<AaregArbeidsforholdOversikt> {
        val callId = MDC.get("trace_id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val token = texasHttpClient.azureAdSystemToken(scope).getOrElse { return UpstreamResult.Failure(it) }
        val response = try {
            httpClient.post(arbeidsforholdOversiktPath) {
                bearerAuth(token)
                header(NAV_CALL_ID_HEADER, callId)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            return UpstreamResult.Failure(UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, e.response.status.value, e))
        } catch (e: Exception) {
            return UpstreamResult.Failure(UpstreamFailure(AAREG, UpstreamFailureStage.REQUEST, null, e))
        }
        return try {
            UpstreamResult.Success(response.body<AaregArbeidsforholdOversikt>())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpstreamResult.Failure(UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, response.status.value, e))
        }
    }

    companion object {
        const val ARBEIDSFORHOLD_OVERSIKT_PATH = "/api/v2/arbeidstaker/arbeidsforholdoversikt"
    }
}
