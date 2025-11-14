package no.nav.syfo.narmesteleder.kafka.model

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import no.nav.syfo.narmesteleder.domain.BehovReason
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite

enum class LeesahStatus {
    NY_LEDER,
    DEAKTIVERT_ARBEIDSTAKER,
    DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING,
    DEAKTIVERT_LEDER,
    DEAKTIVERT_ARBEIDSFORHOLD,
    DEAKTIVERT_NY_LEDER,
    IDENTENDRING,

    @JsonEnumDefaultValue
    UKJENT;
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
    val status: LeesahStatus,
) {

    fun toNlBehovWrite() = LinemanagerRequirementWrite(
        employeeIdentificationNumber = fnr,
        orgNumber = orgnummer,
        managerIdentificationNumber = narmesteLederFnr,
        behovReason = BehovReason.valueOf(status.name),
        revokedLinemanagerId = narmesteLederId,
    )
}
