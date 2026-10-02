package no.nav.syfo.narmestelederrelasjon.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerLookupRequest
import no.nav.syfo.narmestelederrelasjon.api.model.LinemanagerLookupResponse
import no.nav.syfo.narmestelederrelasjon.api.model.toResponse
import no.nav.syfo.narmestelederrelasjon.application.LookupActiveNarmestelederUseCase
import no.nav.syfo.narmestelederrelasjon.observability.COUNT_LOOKUP_NARMESTELEDER
import no.nav.syfo.platform.api.tryReceive
import no.nav.syfo.texas.AzureAdTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINE_MANAGER_LOOKUP_PATH = "/lookup"

fun Route.registerLineManagerLookupApi(
    lookupActiveNarmesteleder: LookupActiveNarmestelederUseCase,
    texasHttpClient: TexasHttpClient,
    preAuthorizedApps: Set<String>,
) {
    route(LINE_MANAGER_LOOKUP_PATH) {
        install(AzureAdTokenAuthPlugin) {
            client = texasHttpClient
            this.preAuthorizedApps = preAuthorizedApps
        }

        post {
            val request = call.tryReceive<LinemanagerLookupRequest>()
            val organizationValue = request.organizationNumber
                ?: throw ApiErrorException.BadRequestException("Missing organizationNumber in request body")
            val organizationNumber = runCatching { OrganizationNumber(organizationValue) }.getOrElse {
                throw ApiErrorException.BadRequestException(
                    "Invalid organizationNumber in request body",
                    type = ErrorType.INVALID_FORMAT,
                )
            }
            val employeeValue = request.employeeNationalIdentificationNumber
                ?: throw ApiErrorException.BadRequestException("Missing employeeNationalIdentificationNumber in request body")
            val employeeNationalIdentificationNumber = runCatching { PersonIdent(employeeValue) }.getOrElse {
                throw ApiErrorException.BadRequestException(
                    "Invalid employeeNationalIdentificationNumber in request body",
                    type = ErrorType.INVALID_FORMAT,
                )
            }
            val activeNarmesteleder = lookupActiveNarmesteleder.execute(
                employeeNationalIdentificationNumber,
                organizationNumber,
            )

            COUNT_LOOKUP_NARMESTELEDER.increment()
            call.respond(HttpStatusCode.OK, LinemanagerLookupResponse(activeNarmesteleder?.toResponse()))
        }
    }
}
