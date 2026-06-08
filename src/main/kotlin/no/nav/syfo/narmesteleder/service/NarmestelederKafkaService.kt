package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

class NarmestelederKafkaService(
    val kafkaSykemeldingProducer: ISykmeldingNLKafkaProducer,
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
                orgnummer = linemanager.orgNumber.value,
                // I den tidligere nærmeste leder-løsningen ble det rapportert hvorvidt arbeidsgiver forskutterer lønn i samme skjema som
                // nærmeste leder. Ved overgangen til Altinn 3 og overføringen av NL til esyfo, gikk man bort fra dette.
                // Denne settes til true for bakoverkompabilitet på Kafka-meldingene fram til helseytelser er ute av Altinn 2.
                utbetalesLonn = true
            ),
            source = source,
        )
    }

    fun avbrytNarmesteLederRelation(
        linemanagerRevoke: LinemanagerRevoke,
        source: NlResponseSource
    ) {
        kafkaSykemeldingProducer.sendSykmldingNLBrudd(
            NlAvbrutt(
                sykmeldtFnr = linemanagerRevoke.employeeIdentificationNumber.value,
                orgnummer = linemanagerRevoke.orgNumber.value,
            ),
            source = source
        )
    }
}
