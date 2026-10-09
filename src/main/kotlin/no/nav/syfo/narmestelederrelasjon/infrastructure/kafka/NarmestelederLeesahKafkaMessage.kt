package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjon
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

/**
 * The value on the Leesah topic that `narmestelederrelasjon` stores and republishes. The module owns
 * this contract, documented in `docs/kafka/syfo-narmesteleder-leesah.schema.json`. Other modules that
 * read the topic keep their own model.
 */
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
    fun toLeesahNarmestelederrelasjon() = LeesahNarmestelederrelasjon(
        narmestelederId = narmesteLederId,
        sykmeldtFnr = fnr,
        orgnummer = orgnummer,
        narmestelederFnr = narmesteLederFnr,
        narmestelederTelefonnummer = narmesteLederTelefonnummer,
        narmestelederEpost = narmesteLederEpost,
        aktivFom = aktivFom,
        aktivTom = aktivTom,
        arbeidsgiverForskutterer = arbeidsgiverForskutterer,
        sourceEmploymentRevocationAt = if (status == LeesahStatus.DEAKTIVERT_ARBEIDSFORHOLD) timestamp.toInstant() else null,
    )
}
