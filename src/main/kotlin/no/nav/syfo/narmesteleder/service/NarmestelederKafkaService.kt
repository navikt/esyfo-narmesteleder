package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
) {
    fun sendNarmesteLederRelation(narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite) {
        kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(narmesteLederRelasjonerWrite.toKafkaModel(), source = NlResponseSource.LPS)
    }

    fun avbrytNarmesteLederRelation(orgnummer: String, sykmeldtFnr: String) {
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            NlAvbrutt(
                orgnummer = orgnummer,
                sykmeldtFnr = sykmeldtFnr
            ),
            source = NlResponseSource.LPS
        )
    }
}
