package no.nav.syfo.narmesteleder.api.internal

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.COUNT_LOOKUP_NARMESTELEDER
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.service.NarmestelederLookup
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.texas.AzureAdTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val NARMESTELEDER_LOOKUP_PATH = "/narmesteleder"
private const val SYKMELDT_FNR_HEADER = "Sykmeldt-Fnr"

data class NarmestelederLookupResponse(
    val narmesteLeder: NarmesteLederResponse?,
)

data class NarmesteLederResponse(
    val fnr: String,
    val epostadresser: List<String>,
)

fun Route.registerNarmestelederLookupApi(
    narmestelederLookupService: NarmestelederLookupService,
    texasHttpClient: TexasHttpClient,
    preAuthorizedApps: Set<String>,
) {
    route(NARMESTELEDER_LOOKUP_PATH) {
        install(AzureAdTokenAuthPlugin) {
            client = texasHttpClient
            this.preAuthorizedApps = preAuthorizedApps
        }

        get {
            val orgnummer = OrganizationNumber.parse(
                call.request.queryParameters["orgnummer"]
                    ?: throw ApiErrorException.BadRequestException("Missing orgnummer parameter")
            ).getOrElse {
                throw ApiErrorException.BadRequestException("Invalid orgnummer parameter", type = ErrorType.INVALID_FORMAT)
            }
            val sykmeldtFnr = PersonalIdentificationNumber.parse(
                call.request.headers[SYKMELDT_FNR_HEADER]
                    ?: throw ApiErrorException.BadRequestException("Missing Sykmeldt-Fnr header")
            ).getOrElse {
                throw ApiErrorException.BadRequestException("Invalid Sykmeldt-Fnr header", type = ErrorType.INVALID_FORMAT)
            }
            val response = narmestelederLookupService.findActiveNarmesteleder(sykmeldtFnr, orgnummer)

            COUNT_LOOKUP_NARMESTELEDER.increment()
            call.respond(HttpStatusCode.OK, NarmestelederLookupResponse(response?.toResponse()))
        }
    }
}

private fun NarmestelederLookup.toResponse() = NarmesteLederResponse(
    fnr = fnr.value,
    epostadresser = epostadresser.map { it.value },
)
