package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authentication
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exceptions.UnauthorizedException
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

const val LINEMANAGER_API_PATH = "/linemanager"
const val REVOKE_PATH = "$LINEMANAGER_API_PATH/revoke"
const val RECUIREMENT_PATH = "$LINEMANAGER_API_PATH/requirement"
fun Route.registerLinemanagerApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    validationService: ValidationService,
    texasHttpClient: TexasHttpClient,
    linemanagerRequirementRestHandler: LinemanagerRequirementRESTHandler,
) {
    route(LINEMANAGER_API_PATH) {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        post() {
            val create = call.tryReceive<Linemanager>()
            val actors = validationService.validateLinemanager(create, call.getMyPrincipal())

            narmestelederKafkaService.sendNarmesteLederRelasjon(
                create,
                actors,
                NlResponseSource.getSourceFrom(call.getMyPrincipal(), create)
            )

            call.respond(HttpStatusCode.Accepted)
        }
    }

    route(REVOKE_PATH) {
        post() {
            val principal = call.getMyPrincipal()
            val revoke = call.tryReceive<LinemanagerRevoke>()
            val employee = validationService.validateLinemanagerRevoke(revoke, principal)

            val tweakedRevoke = revoke.copy(employeeIdentificationNumber = employee.nationalIdentificationNumber)
            narmestelederKafkaService.avbrytNarmesteLederRelation(
                tweakedRevoke,
                NlResponseSource.getSourceFrom(principal, tweakedRevoke)
            )

            call.respond(HttpStatusCode.Accepted)
        }
    }

    route(RECUIREMENT_PATH) {
        put("/{id}") {
            val id = call.getUUIDFromPathVariable(name = "id")
            val linemanager = call.tryReceive<Manager>()

            linemanagerRequirementRestHandler.handleUpdatedRequirement(
                linemanager,
                id,
                principal = call.getMyPrincipal()
            )

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
    }
}

fun RoutingCall.getMyPrincipal(): Principal =
    when (attributes[TOKEN_ISSUER]) {
        JwtIssuer.MASKINPORTEN -> {
            authentication.principal<OrganisasjonPrincipal>() ?: throw UnauthorizedException()
        }

        JwtIssuer.TOKEN_X -> {
            authentication.principal<UserPrincipal>() ?: throw UnauthorizedException()
        }

        else -> throw UnauthorizedException()
    }
