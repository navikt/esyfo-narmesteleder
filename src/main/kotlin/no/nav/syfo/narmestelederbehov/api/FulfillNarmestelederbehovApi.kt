package no.nav.syfo.narmestelederbehov.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.api.getUUIDFromPathVariable
import no.nav.syfo.platform.api.tryReceive
import no.nav.syfo.platform.auth.getMyPrincipal

fun Route.registerFulfillNarmestelederbehovApi(
    fulfillNarmestelederbehov: FulfillNarmestelederbehovUseCase,
) {
    route("$NARMESTELEDERBEHOV_PATH/{id}") {
        put {
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
