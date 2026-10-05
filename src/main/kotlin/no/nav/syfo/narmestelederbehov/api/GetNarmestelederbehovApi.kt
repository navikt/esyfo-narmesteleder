package no.nav.syfo.narmestelederbehov.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.getUUIDFromPathVariable
import no.nav.syfo.narmestelederbehov.application.GetNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.auth.getMyPrincipal
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val GET_NARMESTELEDERBEHOV_PATH = "/linemanager/requirement/{id}"

fun Route.registerGetNarmestelederbehovApi(
    getNarmestelederbehov: GetNarmestelederbehovUseCase,
    texasHttpClient: TexasHttpClient,
) {
    route(GET_NARMESTELEDERBEHOV_PATH) {
        method(HttpMethod.Get) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }
            handle {
                val id = NarmestelederbehovId(call.getUUIDFromPathVariable(name = "id"))
                val subject = call.getMyPrincipal().toOrganizationAccessSubject()
                val response = try {
                    getNarmestelederbehov.execute(id, subject).toLinemanagerRequirementRead()
                } catch (e: ApiErrorException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw ApiErrorException.InternalServerErrorException(
                        "Something went wrong while fetching LinemanagerRequirement",
                        e,
                    )
                }
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
