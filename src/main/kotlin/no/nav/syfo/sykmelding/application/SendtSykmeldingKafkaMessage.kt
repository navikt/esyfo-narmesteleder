package no.nav.syfo.sykmelding.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.MainOrganizationSource
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovSource
import no.nav.syfo.narmestelederbehov.domain.Employee
import java.time.LocalDate

const val SENDT_SYKMELDING_TOPIC = "teamsykmelding.syfo-sendt-sykmelding"

data class SendtSykmeldingKafkaMessage(
    val sykmelding: ArbeidsgiverSykmelding,
    val kafkaMetadata: KafkaMetadata,
    val event: Event,
) {

    fun toCreateNarmestelederbehovCommand(arbeidsgiver: Arbeidsgiver) = CreateNarmestelederbehovCommand(
        employee = Employee(PersonalIdentificationNumber(kafkaMetadata.fnr), OrganizationNumber(arbeidsgiver.orgnummer)),
        manager = null,
        reason = BehovReason.INGEN_LEDER_REGISTRERT,
        revokedRelationId = null,
        sykmeldingKnownActive = sykmelding.sykmeldingsperioder.any { LocalDate.now() in it.fom..it.tom },
        mainOrganization = MainOrganizationSource.FromSykmelding(arbeidsgiver.juridiskOrgnummer),
        source = NarmestelederbehovSource.SendtSykmelding(kafkaMetadata.sykmeldingId),
    )
}
