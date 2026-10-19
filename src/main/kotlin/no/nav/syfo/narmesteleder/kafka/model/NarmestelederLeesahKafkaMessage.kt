package no.nav.syfo.narmesteleder.kafka.model

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.MainOrganizationSource
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovSource
import no.nav.syfo.narmestelederbehov.domain.Employee
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class LeesahStatus {
    NY_LEDER,
    DEAKTIVERT_ARBEIDSTAKER,
    DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING,
    DEAKTIVERT_LEDER,
    DEAKTIVERT_ARBEIDSFORHOLD,
    DEAKTIVERT_NY_LEDER,
    IDENTENDRING,
    DEAKTIVERT_PERSONALLEDER,
    DEAKTIVERT_LPS,

    @JsonEnumDefaultValue
    UKJENT
}

data class NarmestelederLeesahKafkaMessage(
    val narmesteLederId: UUID,
    val fnr: String,
    val orgnummer: String,
    val narmesteLederFnr: String,
    val narmesteLederTelefonnummer: String,
    val narmesteLederEpost: String,
    val aktivFom: LocalDate,
    val aktivTom: LocalDate?,
    val arbeidsgiverForskutterer: Boolean?,
    val timestamp: OffsetDateTime,
    val status: LeesahStatus?,
) {

    fun toCreateNarmestelederbehovCommand() = CreateNarmestelederbehovCommand(
        employee = Employee(PersonIdent(fnr), OrganizationNumber(orgnummer)),
        manager = PersonIdent(narmesteLederFnr),
        reason = status?.name?.let { BehovReason.valueOf(it) }
            ?: BehovReason.UKJENT,
        revokedRelationId = narmesteLederId,
        sykmeldingKnownActive = false,
        mainOrganization = MainOrganizationSource.FromEmployment,
        source = NarmestelederbehovSource.NarmestelederLeesah(narmesteLederId),
    )
}
