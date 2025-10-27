package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.api.v1.EmployeeLeaderConnection
import no.nav.syfo.narmesteleder.api.v1.EmployeeLeaderConnectionDiscontinued
import no.nav.syfo.narmesteleder.api.v1.domain.NarmestelederAktorer
import no.nav.syfo.narmesteleder.kafka.ISykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykemeldingNLKafkaProducer,
) {
    fun sendNarmesteLederRelasjon(
        employeeLeaderConnection: EmployeeLeaderConnection,
        narmestelederAktorer: NarmestelederAktorer,
        source: NlResponseSource,
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(
            NlResponse(
                sykmeldt = Sykmeldt.from(narmestelederAktorer.employee),
                leder = employeeLeaderConnection.leader.toLeder().updateFromPerson(narmestelederAktorer.leader),
                orgnummer = employeeLeaderConnection.orgnumber
            ), source = source
        )
    }

    fun avbrytNarmesteLederRelation(
        employeeLeaderConnectionDiscontinued: EmployeeLeaderConnectionDiscontinued, source: NlResponseSource
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            NlAvbrutt(
                employeeLeaderConnectionDiscontinued.employeeIdentificationNumber,
                employeeLeaderConnectionDiscontinued.orgnumber,
            ), source = source
        )
    }
}
