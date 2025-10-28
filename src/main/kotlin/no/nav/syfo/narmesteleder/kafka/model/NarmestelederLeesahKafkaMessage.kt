package no.nav.syfo.narmesteleder.kafka.model

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import no.nav.syfo.narmesteleder.domain.EmployeeLeaderConnectionWrite

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

    fun toNlBehovWrite() = EmployeeLeaderConnectionWrite(
        employeeIdentificationNumber = fnr,
        orgnumber = orgnummer,
        leaderIdentificationNumber = narmesteLederFnr,
        leesahStatus = status.name,
    )
}
