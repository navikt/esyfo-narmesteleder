package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmestelederbehov.api.throwIfRejected
import no.nav.syfo.narmestelederbehov.api.toManagerContactInput
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.api.tryReceive
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINEMANAGER_API_PATH = "/linemanager"
const val REQUIREMENT_PATH = "$LINEMANAGER_API_PATH/requirement"
fun Route.registerLinemanagerApiV1(
    texasHttpClient: TexasHttpClient,
    linemanagerRequirementRestHandler: LinemanagerRequirementRESTHandler,
    fulfillNarmestelederbehov: FulfillNarmestelederbehovUseCase,
) {
    route(LINEMANAGER_API_PATH) {
        // Requirement routes inherit authentication from this parent.
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }
    }

    route(REQUIREMENT_PATH) {
        put("/{id}") {
            val principal = call.getMyPrincipal()
            fulfillNarmestelederbehov.execute(
                FulfillNarmestelederbehovCommand(
                    behovId = NarmestelederbehovId(call.getUUIDFromPathVariable(name = "id")),
                    manager = call.tryReceive<Manager>().toManagerContactInput(),
                    accessSubject = principal.toOrganizationAccessSubject(),
                ),
            ).throwIfRejected()
            principal.countFulfilledRequirement()
            call.respond(HttpStatusCode.Accepted)
        }

        get("/{id}") {
            val id = call.getUUIDFromPathVariable(name = "id")
            val nlBehov = linemanagerRequirementRestHandler.handleGetLinemanagerRequirement(
                requirementId = id,
                principal = call.getMyPrincipal()
            )
            call.respond(HttpStatusCode.OK, nlBehov)
        }

        get {
            val pageSize = call.getPageSize()
            val createAfter = call.getCreatedAfter()
            val orgNumber = call.getRequiredOrganizationNumberQueryParameter("orgNumber")
            val principal = call.getMyPrincipal()
            val collection = linemanagerRequirementRestHandler.handleGetLinemanagerRequirementsCollection(
                pageSize = pageSize,
                createdAfter = createAfter,
                orgNumber = orgNumber,
                principal = principal,
            )
            call.respond(HttpStatusCode.OK, collection)
        }
    }
}
