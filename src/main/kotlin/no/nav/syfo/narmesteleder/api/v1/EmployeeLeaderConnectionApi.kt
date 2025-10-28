package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authentication
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.exceptions.UnauthorizedException
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.texas.MaskinportenAndTokenXTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

fun Route.registerEmployeeLeaderConnectionApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    validationService: ValidationService,
    texasHttpClient: TexasHttpClient,
    elcRequirementRestHandler: EmployeeLeaderConnectionRequirementRESTHandler
) {
    route("/employeeLeaderConnection") {
        install(MaskinportenAndTokenXTokenAuthPlugin) {
            client = texasHttpClient
        }

        post() {
            val nlRelasjon = call.tryReceive<EmployeeLeaderConnection>()
            val nlAktorer = validationService.validateEmployeLeaderConnection(nlRelasjon, call.getMyPrincipal())

            narmestelederKafkaService.sendNarmesteLederRelasjon(
                nlRelasjon,
                nlAktorer,
                NlResponseSource.LPS,
            )

            call.respond(HttpStatusCode.Accepted)
        }
    }

    route("/employeeLeaderConnection/discontinue") {
        post() {
            val avkreft = call.tryReceive<EmployeeLeaderConnectionDiscontinued>()
            val sykmeldt = validationService.validateEmployeeLeaderConnectionDiscontinue(avkreft, call.getMyPrincipal())
            narmestelederKafkaService.avbrytNarmesteLederRelation(
                avkreft.copy(employeeIdentificationNumber = sykmeldt.nationalIdentificationNumber),
                NlResponseSource.LPS
            )

            call.respond(HttpStatusCode.Accepted)
        }
    }
    
    route("employeeLeaderConnection/requirement") {
        put("/{id}") {
            val id = call.getUUIDFromPathVariable(name = "id")
            val nlRelasjon = call.tryReceive<EmployeeLeaderConnection>()

            elcRequirementRestHandler.handleUpdatedRequirement(nlRelasjon, id, principal = call.getMyPrincipal())

            call.respond(HttpStatusCode.Accepted)
        }

        get("/{id}") {
            val id = call.getUUIDFromPathVariable(name = "id")
            val nlBehov = elcRequirementRestHandler.handleGetEmployeeLeaderRequirement(
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
            authentication.principal<BrukerPrincipal>() ?: throw UnauthorizedException()
        }

        else -> throw UnauthorizedException()
    }
