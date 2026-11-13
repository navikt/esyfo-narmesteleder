package no.nav.syfo.sykmelding.infrastructure.kafka

import java.time.LocalDate
import java.time.OffsetDateTime

/** The part of a sykmeldingsregister record the parser reads; unknown fields are ignored. */
data class SykmeldingsregisterRecordDto(
    val kafkaMetadata: KafkaMetadata,
    val event: Event,
    val sykmelding: Sykmelding,
) {
    data class KafkaMetadata(
        val fnr: String,
    )

    data class Event(
        val timestamp: OffsetDateTime,
        val arbeidsgiver: Arbeidsgiver? = null,
        val brukerSvar: UserAnswers? = null,
    )

    data class Arbeidsgiver(
        val orgnummer: String,
    )

    data class UserAnswers(
        val riktigNarmesteLeder: NarmestelederAnswer? = null,
    )

    data class NarmestelederAnswer(
        val svar: String,
    )

    data class Sykmelding(
        // Nullable elements: Jackson does not null-check list elements.
        val sykmeldingsperioder: List<Sykmeldingsperiode?>,
    )

    data class Sykmeldingsperiode(
        val tom: LocalDate,
    )
}
