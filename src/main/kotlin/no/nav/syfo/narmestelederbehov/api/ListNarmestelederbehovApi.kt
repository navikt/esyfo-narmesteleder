package no.nav.syfo.narmestelederbehov.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmesteleder.api.v1.getRequiredOrganizationNumberQueryParameter
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovQuery
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovUseCase
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val NARMESTELEDERBEHOV_PATH = "/linemanager/requirement"

fun Route.registerListNarmestelederbehovApi(
    listNarmestelederbehov: ListNarmestelederbehovUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(NARMESTELEDERBEHOV_PATH) {
        method(HttpMethod.Get) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }
            handle {
                val pageSize = call.getPageSize()
                val createdAfter = call.getCreatedAfter()
                val orgNumber = call.getRequiredOrganizationNumberQueryParameter("orgNumber")
                val principal = call.getMyPrincipal()
                val response = listNarmestelederbehov.execute(
                    ListNarmestelederbehovQuery(
                        organizationNumber = OrganizationNumber(orgNumber.value),
                        createdAfter = createdAfter,
                        pageSize = pageSize,
                        subject = principal.toOrganizationAccessSubject(),
                    ),
                ).toLinemanagerRequirementCollection(pageSize)
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
