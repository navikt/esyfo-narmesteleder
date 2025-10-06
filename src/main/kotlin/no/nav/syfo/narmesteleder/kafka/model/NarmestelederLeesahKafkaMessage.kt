package no.nav.syfo.narmesteleder.kafka.model

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class NlStatus {
    NY_LEDER,
    DEAKTIVERT_ARBEIDSTAKER,
    DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING,
    DEAKTIVERT_LEDER,
    DEAKTIVERT_ARBEIDSFORHOLD,
    DEAKTIVERT_NY_LEDER,
    IDENTENDRING;

    companion object {
        fun fromStatus(status: String?) =
            entries.firstOrNull {
                it.name.equals(status, ignoreCase = true)
            }
    }
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
    val status: String? = null,
)
