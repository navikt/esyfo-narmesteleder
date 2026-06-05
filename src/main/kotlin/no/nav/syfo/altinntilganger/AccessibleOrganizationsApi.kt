package no.nav.syfo.altinntilganger

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val TILGANGER_API_PATH = "/tilganger"

fun Route.registerTilgangerApi(
    altinnTilgangerService: AltinnTilgangerService,
    texasHttpClient: TexasHttpClient,
) {
    route(TILGANGER_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        get {
            val principal = call.getMyPrincipal()
            if (principal !is UserPrincipal) {
                throw ApiErrorException.ForbiddenException(
                    errorMessage = "Only user principals can access tilganger endpoint",
                    type = ErrorType.AUTHORIZATION_ERROR,
                )
            }
            val organizations = altinnTilgangerService.getFilteredOrganizations(principal)
            call.respond(HttpStatusCode.OK, AccessibleOrganizationsResponse(organizations = organizations))
        }
    }
}
