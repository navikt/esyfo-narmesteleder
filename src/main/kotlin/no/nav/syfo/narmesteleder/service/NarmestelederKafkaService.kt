package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt
import no.nav.syfo.pdl.PdlService

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
    val pdlService: PdlService,
) {
    suspend fun sendNarmesteLederRelation(
        narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite,
        source: NlResponseSource
    ) {
        val sykmeldt = pdlService.getPersonFor(narmesteLederRelasjonerWrite.sykmeldtFnr)
        val leder = pdlService.getPersonFor(narmesteLederRelasjonerWrite.leder.fnr)
        kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(
            NlResponse(
                sykmeldt= Sykmeldt.from(sykmeldt),
                leder = narmesteLederRelasjonerWrite.leder.updateFromPerson(leder),
                orgnummer = narmesteLederRelasjonerWrite.organisasjonsnummer
            ),
            source = source
        )
    }

    suspend fun avbrytNarmesteLederRelation(
        narmestelederRelasjonAvkreft: NarmestelederRelasjonAvkreft,
        source: NlResponseSource
    ) {

        val sykmeldt = pdlService.getPersonFor(narmestelederRelasjonAvkreft.sykmeldtFnr)
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            NlAvbrutt(
                sykmeldt.fnr,
                narmestelederRelasjonAvkreft.organisasjonsnummer,
            ),
            source = source
        )
    }
}
