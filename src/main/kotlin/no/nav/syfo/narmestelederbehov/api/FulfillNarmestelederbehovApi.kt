package no.nav.syfo.narmestelederbehov.api

import arrow.core.raise.either
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.api.getUUIDFromPathVariable
import no.nav.syfo.platform.api.respondApi
import no.nav.syfo.platform.api.toApiResponse
import no.nav.syfo.platform.api.tryReceive
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.platform.auth.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient

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
                val response = either {
                    val principal = call.getMyPrincipal()
                    val behovId = NarmestelederbehovId(call.getUUIDFromPathVariable(name = "id"))
                    val manager = call.tryReceive<Manager>()
                    fulfillNarmestelederbehov.execute(
                        FulfillNarmestelederbehovCommand(
                            behovId = behovId,
                            manager = manager.toManagerContactInput(),
                            accessSubject = principal.toOrganizationAccessSubject(),
                        ),
                    ).mapLeft { it.toApiRejection() }.bind()
                    principal.countFulfilledRequirement()
                }.toApiResponse(HttpStatusCode.Accepted)

                call.respondApi(response)
            }
        }
    }
}
