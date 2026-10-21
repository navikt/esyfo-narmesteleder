package no.nav.syfo.narmestelederrelasjon.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.api.model.toResponse
import no.nav.syfo.narmestelederrelasjon.application.employee.ListActiveNarmesteledereForEmployeeUseCase
import no.nav.syfo.narmestelederrelasjon.observability.countEmployeeLinemanager
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.auth.UserPrincipal
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.platform.auth.texas.TokenXTokenAuthPlugin
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

const val EMPLOYEE_LINEMANAGER_API_PATH = "/employee/linemanager"

private const val ORG_NUMBER_QUERY_PARAMETER = "orgNumber"

fun Route.registerEmployeeLinemanagerApi(
    listActiveNarmesteledereForEmployee: ListActiveNarmesteledereForEmployeeUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(EMPLOYEE_LINEMANAGER_API_PATH) {
        install(TokenXTokenAuthPlugin) {
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
            // The employee identity is derived solely from the authenticated TokenX user's `pid`.
            // Altinn validation is not required because callers can retrieve only their own relationships.
            val employee = runCatching { PersonalIdentificationNumber(principal.ident) }
                .getOrElse {
                    throw ApiErrorException.UnauthorizedException("Invalid token subject")
                }
            val orgNumber = call.getOptionalOrganizationNumberQueryParameter()
            val narmesteledere = listActiveNarmesteledereForEmployee.execute(employee, orgNumber)
            countEmployeeLinemanager(filtered = orgNumber != null)
            call.respond(HttpStatusCode.OK, narmesteledere.toResponse())
        }
    }
}

private fun RoutingCall.getOptionalOrganizationNumberQueryParameter(): OrganizationNumber? {
    val values = queryParameters.getAll(ORG_NUMBER_QUERY_PARAMETER) ?: return null
    if (values.size != 1) {
        throw ApiErrorException.BadRequestException("Expected exactly one $ORG_NUMBER_QUERY_PARAMETER parameter")
    }
    return runCatching { OrganizationNumber(values.single()) }
        .getOrElse {
            throw ApiErrorException.BadRequestException(
                it.message ?: "Invalid organization number format for $ORG_NUMBER_QUERY_PARAMETER parameter",
                type = ErrorType.INVALID_FORMAT,
            )
        }
}
