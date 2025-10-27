package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.api.v1.EmployeeLeaderRelationWrite
import no.nav.syfo.narmesteleder.api.v1.EmployeeLeaderRelationDiscontinued
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
        employeeLeaderRelationWrite: EmployeeLeaderRelationWrite,
        narmestelederAktorer: NarmestelederAktorer,
        source: NlResponseSource,
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLRelasjon(
            NlResponse(
                sykmeldt = Sykmeldt.from(narmestelederAktorer.employee),
                leder = employeeLeaderRelationWrite.leader.toLeder().updateFromPerson(narmestelederAktorer.leader),
                orgnummer = employeeLeaderRelationWrite.orgnumber
            ), source = source
        )
    }

    fun avbrytNarmesteLederRelation(
        employeeLeaderRelationDiscontinued: EmployeeLeaderRelationDiscontinued, source: NlResponseSource
    ) {
        kafkaSykemeldingProducer.sendSykemeldingNLBrudd(
            NlAvbrutt(
                employeeLeaderRelationDiscontinued.employeeIdentificationNumber,
                employeeLeaderRelationDiscontinued.orgnumber,
            ), source = source
        )
    }
}
