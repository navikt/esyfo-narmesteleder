package no.nav.syfo.sykmelding.infrastructure.kafka

import java.time.LocalDate
import java.time.OffsetDateTime

/** The part of a sykmeldingsregister record the parser reads; unknown fields are ignored. */
data class SendtSykmeldingDto(
    val kafkaMetadata: KafkaMetadataDto,
    val event: EventDto,
    val sykmelding: SykmeldingDto,
)

data class KafkaMetadataDto(
    val fnr: String,
)

data class EventDto(
    val timestamp: OffsetDateTime,
    val arbeidsgiver: ArbeidsgiverDto? = null,
    val brukerSvar: BrukerSvarDto? = null,
)

data class ArbeidsgiverDto(
    val orgnummer: String,
)

data class BrukerSvarDto(
    val riktigNarmesteLeder: RiktigNarmesteLederDto? = null,
)

data class RiktigNarmesteLederDto(
    val svar: String,
)

data class SykmeldingDto(
    // Nullable elements: Jackson does not null-check list elements.
    val sykmeldingsperioder: List<SykmeldingsperiodeDto?>,
)

data class SykmeldingsperiodeDto(
    val tom: LocalDate,
)
