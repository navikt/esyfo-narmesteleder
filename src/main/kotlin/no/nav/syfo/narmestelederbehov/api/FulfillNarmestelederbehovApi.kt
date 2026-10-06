package no.nav.syfo.narmestelederbehov.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.api.v1.countFulfilledRequirement
import no.nav.syfo.narmesteleder.api.v1.getUUIDFromPathVariable
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.api.tryReceive
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

fun Route.registerFulfillNarmestelederbehovApi(
    fulfillNarmestelederbehov: FulfillNarmestelederbehovUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route("$NARMESTELEDERBEHOV_PATH/{id}") {
        method(HttpMethod.Put) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }
            handle {
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
        }
    }
}
