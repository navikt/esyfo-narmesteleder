package no.nav.syfo.narmestelederrelasjon.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import no.nav.syfo.narmesteleder.api.internal.INTERNAL_API_V1_PATH
import no.nav.syfo.narmesteleder.api.v1.getMyPrincipal
import no.nav.syfo.narmestelederrelasjon.api.model.toResponse
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.observability.countGetNarmestelederrelasjon
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient
import java.util.UUID

private const val NARMESTELEDERRELASJON_RELATIVE_PATH = "/linemanager/{id}"
const val NARMESTELEDERRELASJON_API_PATH = "$INTERNAL_API_V1_PATH$NARMESTELEDERRELASJON_RELATIVE_PATH"

fun Route.registerNarmestelederrelasjonApi(
    getNarmestelederrelasjon: GetNarmestelederrelasjon,
    texasHttpClient: TexasHttpClient,
) {
    route(NARMESTELEDERRELASJON_RELATIVE_PATH) {
        method(HttpMethod.Get) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }

            handle {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val id = call.parameters["id"]?.toUuidOrNull()
                if (id == null) {
                    countGetNarmestelederrelasjon(GetNarmestelederrelasjonResult.NotFound)
                    call.respond(HttpStatusCode.NotFound)
                    return@handle
                }

                val principal = call.getMyPrincipal()
                val result = getNarmestelederrelasjon.execute(id, principal)
                countGetNarmestelederrelasjon(result)

                when (result) {
                    is GetNarmestelederrelasjonResult.Found ->
                        call.respond(
                            HttpStatusCode.OK,
                            result.relation.toResponse(result.employeeName, result.organizationName),
                        )

                    GetNarmestelederrelasjonResult.NotFound -> call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
