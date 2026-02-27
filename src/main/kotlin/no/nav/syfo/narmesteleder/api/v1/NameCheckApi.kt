package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.syfo.application.environment.isProdEnv
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val NAME_CHECK_API_PATH = "/person/namecheck"
fun Route.registerNameCheckApiV1(
    texasHttpClient: TexasHttpClient,
    pdlService: PdlService,
) {
    route(NAME_CHECK_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        post {
            if (isProdEnv()) {
                call.respond(HttpStatusCode.NotImplemented, "Namecheck is not allowed in prod")
                return@post
            }
            val nameCheck = call.tryReceive<NameCheck>()
            val person = pdlService.getPersonOrThrowApiError(nameCheck.employeeIdentificationNumber)
            if (person.name.etternavn.uppercase() != nameCheck.lastName.uppercase()) {
                call.respond(HttpStatusCode.BadRequest, "Last name does not match")
                return@post
            } else {
                call.respond(HttpStatusCode.Accepted, "Last name matches")
            }
        }
    }
}
