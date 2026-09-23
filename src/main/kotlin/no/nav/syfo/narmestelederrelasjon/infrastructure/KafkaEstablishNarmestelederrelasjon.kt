package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonCommand
import no.nav.syfo.narmestelederrelasjon.application.RelationSource

class KafkaEstablishNarmestelederrelasjon(private val producer: ISykmeldingNLKafkaProducer) : EstablishNarmestelederrelasjon {
    override suspend fun establish(command: EstablishNarmestelederrelasjonCommand) {
        producer.sendSykmeldingNLRelasjon(
            NlResponse(
                orgnummer = command.organizationNumber.value,
                utbetalesLonn = true,
                sykmeldt = Sykmeldt(
                    fnr = command.employee.personIdent.value,
                    navn = listOfNotNull(command.employee.firstName, command.employee.middleName, command.employee.lastName).joinToString(" "),
                ),
                leder = Leder(
                    fnr = command.manager.personIdent.value,
                    mobil = command.manager.mobile,
                    epost = command.manager.email,
                    fornavn = command.manager.firstName,
                    etternavn = command.manager.lastName,
                ),
            ),
            when (command.source) {
                RelationSource.LPS -> NlResponseSource.LPS
                RelationSource.PERSONNEL_MANAGER -> NlResponseSource.PERSONALLEDER
            },
        )
    }
}
