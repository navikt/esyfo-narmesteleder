package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.api.v1.domain.NarmestelederAktorer
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
) {
    fun sendNarmesteLederRelation(
        narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite,
        narmestelederAktorer: NarmestelederAktorer,
        source: NlResponseSource,
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(
            NlResponse(
                sykmeldt = Sykmeldt.from(narmestelederAktorer.sykmeldt),
                leder = narmesteLederRelasjonerWrite.leder.updateFromPerson(narmestelederAktorer.leder),
                orgnummer = narmesteLederRelasjonerWrite.organisasjonsnummer
            ), source = source
        )
    }

    fun avbrytNarmesteLederRelation(
        narmestelederRelasjonAvkreft: NarmestelederRelasjonAvkreft, source: NlResponseSource
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            NlAvbrutt(
                narmestelederRelasjonAvkreft.sykmeldtFnr,
                narmestelederRelasjonAvkreft.organisasjonsnummer,
            ), source = source
        )
    }
}
