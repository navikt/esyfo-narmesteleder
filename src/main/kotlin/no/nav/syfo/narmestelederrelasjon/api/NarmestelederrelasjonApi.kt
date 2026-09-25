package no.nav.syfo.narmestelederrelasjon.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.internal.INTERNAL_API_V1_PATH
import no.nav.syfo.narmesteleder.api.v1.getMyPrincipal
import no.nav.syfo.narmesteleder.api.v1.getUUIDFromPathVariable
import no.nav.syfo.narmestelederrelasjon.api.model.toResponse
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonResult
import no.nav.syfo.narmestelederrelasjon.observability.countGetNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.observability.countLinemanagerRevokeById
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.logger
import java.util.UUID

private val logger = logger("no.nav.syfo.narmestelederrelasjon.api.NarmestelederrelasjonApi")

private const val NARMESTELEDERRELASJON_RELATIVE_PATH = "/linemanager/{id}"
const val NARMESTELEDERRELASJON_API_PATH = "$INTERNAL_API_V1_PATH$NARMESTELEDERRELASJON_RELATIVE_PATH"

fun Route.registerNarmestelederrelasjonApi(
    getNarmestelederrelasjon: GetNarmestelederrelasjon,
    revokeNarmestelederrelasjon: RevokeNarmestelederrelasjon,
    texasHttpClient: TexasHttpClient,
) {
    route(NARMESTELEDERRELASJON_RELATIVE_PATH) {
        // Ktor shares the {id} node with GET; authentication must be installed on the DELETE method node.
        method(HttpMethod.Delete) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }
            handle {
                val principal = call.getMyPrincipal()
                val id = call.getUUIDFromPathVariable(name = "id")
                val result = revokeNarmestelederrelasjon.execute(id, principal.toOrganizationAccessSubject())
                countLinemanagerRevokeById(result)
                when (result) {
                    is RevokeNarmestelederrelasjonResult.Revoked -> call.respond(HttpStatusCode.Accepted)
                    RevokeNarmestelederrelasjonResult.AlreadyRevoked -> {
                        logger.info("Revoke request for an already revoked linemanager relation. {}", call.revokeContext(principal))
                        call.respond(HttpStatusCode.Accepted)
                    }
                    is RevokeNarmestelederrelasjonResult.NotFound -> {
                        if (result.reason == RevokeNarmestelederrelasjonResult.Reason.RELATION_NOT_FOUND) {
                            logger.info("Revoke request for unknown linemanager relation. {}", call.revokeContext(principal))
                        }
                        throw result.toRevokeNotFoundException()
                    }
                }
            }
        }
        method(HttpMethod.Get) {
            install(MaskinportenAndTokenXTokenAuthPlugin) {
                client = texasHttpClient
            }

            handle {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val id = call.parameters["id"]?.toUuidOrNull()
                if (id == null) {
                    countGetNarmestelederrelasjon(
                        GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.INVALID_ID),
                    )
                    throw notFoundException()
                }

                val result = getNarmestelederrelasjon.execute(
                    id = id,
                    accessSubject = call.getMyPrincipal().toOrganizationAccessSubject(),
                )
                countGetNarmestelederrelasjon(result)

                when (result) {
                    is GetNarmestelederrelasjonResult.Found ->
                        call.respond(
                            HttpStatusCode.OK,
                            result.relation.toResponse(result.organizationName),
                        )

                    is GetNarmestelederrelasjonResult.NotFound -> throw notFoundException()

                    GetNarmestelederrelasjonResult.Unavailable ->
                        throw ApiErrorException.InternalServerErrorException()
                }
            }
        }
    }
}

internal fun RevokeNarmestelederrelasjonResult.NotFound.toRevokeNotFoundException() = ApiErrorException.NotFoundException(
    errorMessage = "Linemanager relation not found",
    type = ErrorType.NOT_FOUND,
    isAlreadyLogged = reason == RevokeNarmestelederrelasjonResult.Reason.ACCESS_DENIED,
)

private fun notFoundException() = ApiErrorException.NotFoundException(
    errorMessage = "Linemanager relation was not found",
    includePath = false,
)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

private fun ApplicationCall.revokeContext(principal: Principal): String = "operation=${request.httpMethod.value} ${request.path()}, " +
    "callId=${callId ?: "missing"}, principalType=${principal::class.simpleName}"
