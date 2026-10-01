package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlAvbrutt
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponseSource
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: SykmeldingNarmestelederProducer,
) {
    fun avbrytNarmesteLederRelation(
        linemanagerRevoke: LinemanagerRevoke,
        source: NlResponseSource
    ) = avbrytNarmesteLederRelation(
        employeeIdentificationNumber = linemanagerRevoke.employeeIdentificationNumber,
        orgNumber = linemanagerRevoke.orgNumber,
        source = source,
    )

    fun avbrytNarmesteLederRelation(
        employeeIdentificationNumber: PersonalIdentificationNumber,
        orgNumber: OrganizationNumber,
        source: NlResponseSource,
    ) {
        kafkaSykemeldingProducer.sendSykmldingNLBrudd(
            NlAvbrutt(
                sykmeldtFnr = employeeIdentificationNumber.value,
                orgnummer = orgNumber.value,
            ),
            source = source
        )
    }
}
