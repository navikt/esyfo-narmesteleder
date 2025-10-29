package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.api.v1.Linemanager
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRevoke
import no.nav.syfo.narmesteleder.api.v1.LinemanagerActors
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
) {
    fun sendNarmesteLederRelasjon(
        linemanager: Linemanager,
        linemanagerActors: LinemanagerActors,
        source: NlResponseSource,
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(
            NlResponse(
                sykmeldt = Sykmeldt.from(linemanagerActors.employee),
                leder = linemanager.manager.toLeder().updateFromPerson(linemanagerActors.manager),
                orgnummer = linemanager.orgnumber
            ), source = source
        )
    }

    fun avbrytNarmesteLederRelation(
        linemanagerRevoke: LinemanagerRevoke, source: NlResponseSource
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            NlAvbrutt(
                linemanagerRevoke.employeeIdentificationNumber,
                linemanagerRevoke.orgnumber,
            ), source = source
        )
    }
}
