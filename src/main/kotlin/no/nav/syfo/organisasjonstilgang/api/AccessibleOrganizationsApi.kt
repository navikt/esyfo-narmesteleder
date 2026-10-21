package no.nav.syfo.organisasjonstilgang.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganization
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsResult
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsUseCase
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.api.toUpstreamUnavailableException
import no.nav.syfo.platform.auth.UserPrincipal
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.platform.auth.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

const val ACCESSIBLE_ORGANIZATIONS_API_PATH = "/access/organizations"

data class AccessibleOrganizationsResponse(
    val organizations: List<AccessibleOrganizationResponse>,
)

data class AccessibleOrganizationResponse(
    val orgNumber: String,
    val name: String,
    val subOrganizations: List<AccessibleOrganizationResponse>,
)

fun Route.registerAccessibleOrganizationsApi(
    listAccessibleOrganizations: ListAccessibleOrganizationsUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(ACCESSIBLE_ORGANIZATIONS_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        get {
            val principal = call.getMyPrincipal()
            if (principal !is UserPrincipal) {
                throw ApiErrorException.ForbiddenException(
                    errorMessage = "Only user principals can access accessible organizations endpoint",
                    type = ErrorType.AUTHORIZATION_ERROR,
                )
            }
            when (val result = listAccessibleOrganizations.execute(principal.toPersonnelManager())) {
                is ListAccessibleOrganizationsResult.Listed -> call.respond(
                    HttpStatusCode.OK,
                    AccessibleOrganizationsResponse(organizations = result.organizations.map { it.toResponse() }),
                )

                is ListAccessibleOrganizationsResult.Unavailable -> throw result.failure.toUpstreamUnavailableException()
            }
        }
    }
}

private fun AccessibleOrganization.toResponse(): AccessibleOrganizationResponse = AccessibleOrganizationResponse(
    orgNumber = organizationNumber,
    name = name,
    subOrganizations = subOrganizations.map { it.toResponse() },
)
