package no.nav.syfo.sykmelding.infrastructure.kafka

import no.nav.syfo.platform.kafka.jacksonMapper
import java.time.LocalDate
import java.time.OffsetDateTime

fun sykmeldingsregisterRecordDto(
    fnr: String = "01019012345",
    arbeidsgiver: ArbeidsgiverDto? = ArbeidsgiverDto(orgnummer = "987654321"),
    sykmeldingsperioder: List<SykmeldingsperiodeDto?> = listOf(SykmeldingsperiodeDto(tom = LocalDate.parse("2026-11-20"))),
    svar: String? = null,
    timestamp: OffsetDateTime = OffsetDateTime.parse("2026-11-02T10:15:30+01:00"),
) = SykmeldingsregisterRecordDto(
    kafkaMetadata = KafkaMetadataDto(fnr = fnr),
    event = EventDto(
        timestamp = timestamp,
        arbeidsgiver = arbeidsgiver,
        brukerSvar = BrukerSvarDto(riktigNarmesteLeder = svar?.let { RiktigNarmesteLederDto(svar = it) }),
    ),
    sykmelding = SykmeldingDto(sykmeldingsperioder = sykmeldingsperioder),
)

fun SykmeldingsregisterRecordDto.toJson(): String = jacksonMapper().writeValueAsString(this)
