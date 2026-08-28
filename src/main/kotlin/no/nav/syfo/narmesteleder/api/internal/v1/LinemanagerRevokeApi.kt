package no.nav.syfo.narmesteleder.api.internal.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.api.v1.getMyPrincipal
import no.nav.syfo.narmesteleder.api.v1.getUUIDFromPathVariable
import no.nav.syfo.narmesteleder.service.LinemanagerRevokeService
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

/**
 * The auth plugin is installed on the `{id}` node, not on `/linemanager`. Ktor reuses routing nodes
 * with equal selectors, so installing on `/linemanager` would also apply the plugin to the sibling
 * routes `/linemanager/search` and `/linemanager/statistics`, which install their own.
 *
 * The endpoint accepts both TokenX and Maskinporten because it is expected to move out of the
 * internal API later. Authorization is handled in [LinemanagerRevokeService].
 */
const val LINEMANAGER_REVOKE_BY_ID_PATH = "/linemanager/{id}"

fun Route.registerLinemanagerRevokeApi(
    texasHttpClient: TexasHttpClient,
    linemanagerRevokeService: LinemanagerRevokeService,
) {
    route(LINEMANAGER_REVOKE_BY_ID_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        delete {
            val principal = call.getMyPrincipal()
            val narmestelederId = call.getUUIDFromPathVariable(name = "id")
            val outcome = linemanagerRevokeService.revoke(
                narmestelederId = narmestelederId,
                principal = principal,
                context = "operation=${call.request.httpMethod.value} ${call.request.path()}, " +
                    "callId=${call.callId ?: "missing"}, principalType=${principal::class.simpleName}",
            )
            countLinemanagerRevokeById(outcome)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
