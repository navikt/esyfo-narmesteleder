package no.nav.syfo.narmesteleder.api.internal

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.COUNT_LOOKUP_NARMESTELEDER
import no.nav.syfo.narmesteleder.api.v1.tryReceive
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.service.NarmestelederLookup
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.texas.AzureAdTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val NARMESTELEDER_LOOKUP_PATH = "/narmesteleder"

data class NarmestelederLookupRequest(
    val sykmeldtFnr: String?,
    val orgnummer: String?,
)

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

        post {
            val request = call.tryReceive<NarmestelederLookupRequest>()
            val orgnummer = OrganizationNumber.parse(
                request.orgnummer
                    ?: throw ApiErrorException.BadRequestException("Missing orgnummer in request body")
            ).getOrElse {
                throw ApiErrorException.BadRequestException("Invalid orgnummer in request body", type = ErrorType.INVALID_FORMAT)
            }
            val sykmeldtFnr = PersonalIdentificationNumber.parse(
                request.sykmeldtFnr
                    ?: throw ApiErrorException.BadRequestException("Missing sykmeldtFnr in request body")
            ).getOrElse {
                throw ApiErrorException.BadRequestException("Invalid sykmeldtFnr in request body", type = ErrorType.INVALID_FORMAT)
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
