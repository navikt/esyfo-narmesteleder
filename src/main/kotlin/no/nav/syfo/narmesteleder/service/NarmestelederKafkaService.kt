package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
) {
    fun sendNarmesteLederRelation(narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite) {
        kafkaSykemeldingProducer.sendSykemeldingNL(narmesteLederRelasjonerWrite.toKafkaModel())
    }
}
