package no.nav.syfo.narmesteleder.api.v1

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.narmesteleder.NarmesteLederValidator.nlvalidate
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.texas.MaskinportenTokenAuthPlugin
import no.nav.syfo.texas.client.TexasHttpClient

fun Route.registerNarmestelederApiV1(
    narmestelederKafkaService: NarmestelederKafkaService,
    texasHttpClient: TexasHttpClient,
    aaregService: AaregService,
) {
    route("/narmesteleder") {
        install(MaskinportenTokenAuthPlugin) {
            client = texasHttpClient
        }
        post() {
            val nlRelasjon = call.tryReceive<NarmesteLederRelasjonerWrite>()
            val innsenderOrgNumber = maskinportenIdToOrgnumber(call.consumerIdFromPrincipal())
            val nlArbeidsforhold = aaregService.findEmployeesOrgNumbers(nlRelasjon.leder.fnr)
            val sykemeldtArbeidsforhold = aaregService.findEmployeesOrgNumbers(nlRelasjon.sykmeldtFnr)

            nlvalidate {
                isNotEmpty(nlArbeidsforhold)
                isNotEmpty(sykemeldtArbeidsforhold)
                matchOne(nlArbeidsforhold, nlRelasjon.organisasjonsnummer)

                checkArbeidsforhold(
                    nlOrgNumber = nlRelasjon.organisasjonsnummer,
                    validOrgNumbers = sykemeldtArbeidsforhold,
                    innsenderEmployerOrgnr = innsenderOrgNumber
                )
            }

            narmestelederKafkaService.sendNarmesteLederRelation(nlRelasjon, NlResponseSource.LPS)

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
