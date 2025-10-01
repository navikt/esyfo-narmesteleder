package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authentication
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
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
import no.nav.syfo.texas.MaskinportenTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

fun Route.registerNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    validationService: ValidationService,
    texasHttpClient: TexasHttpClient,
) {
    route("/narmesteleder") {
        install(MaskinportenTokenAuthPlugin) {
            client = texasHttpClient
        }

        post() {
            val nlRelasjon = call.tryReceive<NarmesteLederRelasjonerWrite>()
            val nlAktorer = validationService.validdateNarmesteleder(nlRelasjon, call.getMyPrincipal())

            narmestelederKafkaService.sendNarmesteLederRelation(
                nlRelasjon,
                nlAktorer,
                NlResponseSource.LPS,
            )

            call.respond(HttpStatusCode.Accepted)
        }
    }

    route("/narmesteleder/avkreft") {
        post() {
            val avkreft = call.tryReceive<NarmestelederRelasjonAvkreft>()

            narmestelederKafkaService.avbrytNarmesteLederRelation(avkreft, NlResponseSource.LPS)

            call.respond(HttpStatusCode.Accepted)
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
