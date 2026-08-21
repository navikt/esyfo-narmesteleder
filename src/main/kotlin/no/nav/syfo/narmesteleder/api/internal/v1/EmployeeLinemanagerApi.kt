package no.nav.syfo.narmesteleder.api.internal.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.getMyPrincipal
import no.nav.syfo.narmesteleder.api.v1.getOptionalOrganizationNumberQueryParameter
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.service.EmployeeLinemanagerService
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val EMPLOYEE_LINEMANAGER_API_PATH = "/employee/linemanager"

fun Route.registerEmployeeLinemanagerApi(
    texasHttpClient: TexasHttpClient,
    employeeLinemanagerService: EmployeeLinemanagerService,
) {
    route(EMPLOYEE_LINEMANAGER_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        get {
            val principal = call.getMyPrincipal()
            if (principal !is UserPrincipal) {
                throw ApiErrorException.ForbiddenException(
                    errorMessage = "Forbidden",
                    type = ErrorType.AUTHORIZATION_ERROR,
                )
            }
            val employee = PersonalIdentificationNumber.parse(principal.ident)
                .getOrElse {
                    throw ApiErrorException.UnauthorizedException("Invalid token subject")
                }
            val orgNumber = call.getOptionalOrganizationNumberQueryParameter("orgNumber")
            val collection = employeeLinemanagerService.findActiveLinemanagersForEmployee(employee, orgNumber)
            countEmployeeLinemanager(filtered = orgNumber != null)
            call.respond(HttpStatusCode.OK, collection)
        }
    }
}
