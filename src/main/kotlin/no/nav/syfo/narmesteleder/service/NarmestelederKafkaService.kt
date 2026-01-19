package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
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
        kafkaSykemeldingProducer.sendSykmeldingNLRelasjon(
            NlResponse(
                sykmeldt = Sykmeldt.from(linemanagerActors.employee),
                leder = linemanager.manager.toLeder(linemanagerActors.manager),
                orgnummer = linemanager.orgNumber
            ),
            source = source
        )
    }

    fun avbrytNarmesteLederRelation(
        linemanagerRevoke: LinemanagerRevoke,
        source: NlResponseSource
    ) {
        kafkaSykemeldingProducer.sendSykmldingNLBrudd(
            NlAvbrutt(
                sykmeldtFnr = linemanagerRevoke.employeeIdentificationNumber,
                orgnummer = linemanagerRevoke.orgNumber,
            ),
            source = source
        )
    }
}
