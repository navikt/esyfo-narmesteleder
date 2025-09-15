package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
) {
    fun sendNarmesteLederRelation(
        narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite,
        source: NlResponseSource
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(
            narmesteLederRelasjonerWrite.toNlResponse(),
            source = source
        )
    }

    fun avbrytNarmesteLederRelation(
        narmestelederRelasjonAvkreft: NarmestelederRelasjonAvkreft,
        source: NlResponseSource
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            narmestelederRelasjonAvkreft.toNlAvbrutt(),
            source = source
        )
    }
}
