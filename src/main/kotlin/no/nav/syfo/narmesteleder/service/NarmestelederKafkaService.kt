package no.nav.syfo.narmesteleder.service

import io.ktor.server.plugins.*
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.AaregClientException
import no.nav.syfo.application.exception.InternalServerErrorException
import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
    val pdlService: PdlService,
    val aaregService: AaregService,
) {
    suspend fun sendNarmesteLederRelation(
        narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite,
        source: NlResponseSource,
        innsenderOrganizationNumber: String
    ) {
        try {
            val nlArbeidsforhold = aaregService.findOrgNumbersByPersonIdent(narmesteLederRelasjonerWrite.leder.fnr)
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(narmesteLederRelasjonerWrite.sykmeldtFnr)
            val sykmeldt = pdlService.getPersonFor(narmesteLederRelasjonerWrite.sykmeldtFnr)
            val leder = pdlService.getPersonFor(narmesteLederRelasjonerWrite.leder.fnr)

            validateNarmesteLeder(
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                narmesteLederOrgNumbers = nlArbeidsforhold,
                innsenderOrgNumber = innsenderOrganizationNumber
            )

            kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(
                NlResponse(
                    sykmeldt = Sykmeldt.from(sykmeldt),
                    leder = narmesteLederRelasjonerWrite.leder.updateFromPerson(leder),
                    orgnummer = narmesteLederRelasjonerWrite.organisasjonsnummer
                ),
                source = source
            )
        } catch (e: PdlResourceNotFoundException) {
            throw BadRequestException("Could not find one or both of the persons")
        } catch (e: PdlRequestException) {
            throw InternalServerErrorException("Error when validating persons")
        } catch (e: ValidateNarmesteLederException) {
            throw BadRequestException("Error when validating persons")
        } catch (e: AaregClientException) {
            throw InternalServerErrorException("Error when validating persons")
        }
    }

    suspend fun avbrytNarmesteLederRelation(
        narmestelederRelasjonAvkreft: NarmestelederRelasjonAvkreft, source: NlResponseSource
    ) {
        val sykmeldt = try {
            pdlService.getPersonFor(narmestelederRelasjonAvkreft.sykmeldtFnr)
        } catch (e: PdlResourceNotFoundException) {
            throw BadRequestException("Could not find sykmeldt for provided fnr")
        } catch (e: PdlRequestException) {
            throw InternalServerErrorException("Error when validating fnr for sykmeldt")
        }
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            NlAvbrutt(
                sykmeldt.fnr,
                narmestelederRelasjonAvkreft.organisasjonsnummer,
            ), source = source
        )
    }
}
