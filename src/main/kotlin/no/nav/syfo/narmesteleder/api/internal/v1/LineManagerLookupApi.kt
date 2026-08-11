package no.nav.syfo.narmesteleder.api.internal.v1

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

const val LINE_MANAGER_LOOKUP_PATH = "/lookup"

data class LineManagerLookupRequest(
    val employeeNationalIdentificationNumber: String?,
    val organizationNumber: String?,
)

data class LineManagerLookupResponse(
    val lineManager: LineManagerResponse?,
)

data class LineManagerResponse(
    val nationalIdentificationNumber: String,
    val emailAddresses: List<String>,
)

fun Route.registerLineManagerLookupApi(
    narmestelederLookupService: NarmestelederLookupService,
    texasHttpClient: TexasHttpClient,
    preAuthorizedApps: Set<String>,
) {
    route(LINE_MANAGER_LOOKUP_PATH) {
        install(AzureAdTokenAuthPlugin) {
            client = texasHttpClient
            this.preAuthorizedApps = preAuthorizedApps
        }

        post {
            val request = call.tryReceive<LineManagerLookupRequest>()
            val organizationNumber = OrganizationNumber.parse(
                request.organizationNumber
                    ?: throw ApiErrorException.BadRequestException("Missing organizationNumber in request body")
            ).getOrElse {
                throw ApiErrorException.BadRequestException("Invalid organizationNumber in request body", type = ErrorType.INVALID_FORMAT)
            }
            val employeeNationalIdentificationNumber = PersonalIdentificationNumber.parse(
                request.employeeNationalIdentificationNumber
                    ?: throw ApiErrorException.BadRequestException("Missing employeeNationalIdentificationNumber in request body")
            ).getOrElse {
                throw ApiErrorException.BadRequestException(
                    "Invalid employeeNationalIdentificationNumber in request body",
                    type = ErrorType.INVALID_FORMAT
                )
            }
            val response = narmestelederLookupService.findActiveNarmesteleder(
                employeeNationalIdentificationNumber,
                organizationNumber
            )

            COUNT_LOOKUP_NARMESTELEDER.increment()
            call.respond(HttpStatusCode.OK, LineManagerLookupResponse(response?.toResponse()))
        }
    }
}

private fun NarmestelederLookup.toResponse() = LineManagerResponse(
    nationalIdentificationNumber = fnr.value,
    emailAddresses = epostadresser.map { it.value },
)
