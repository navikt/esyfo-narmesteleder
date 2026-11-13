package no.nav.syfo.sykmelding.infrastructure.kafka

import no.nav.syfo.platform.kafka.jacksonMapper
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.Arbeidsgiver
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.Event
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.KafkaMetadata
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.NarmestelederAnswer
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.Sykmelding
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.Sykmeldingsperiode
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.UserAnswers
import java.time.LocalDate
import java.time.OffsetDateTime

fun sykmeldingsregisterRecordDto(
    fnr: String = "01019012345",
    arbeidsgiver: Arbeidsgiver? = Arbeidsgiver(orgnummer = "987654321"),
    sykmeldingsperioder: List<Sykmeldingsperiode?> = listOf(Sykmeldingsperiode(tom = LocalDate.parse("2026-11-20"))),
    svar: String? = null,
    timestamp: OffsetDateTime = OffsetDateTime.parse("2026-11-02T10:15:30+01:00"),
) = SykmeldingsregisterRecordDto(
    kafkaMetadata = KafkaMetadata(fnr = fnr),
    event = Event(
        timestamp = timestamp,
        arbeidsgiver = arbeidsgiver,
        brukerSvar = UserAnswers(riktigNarmesteLeder = svar?.let { NarmestelederAnswer(svar = it) }),
    ),
    sykmelding = Sykmelding(sykmeldingsperioder = sykmeldingsperioder),
)

fun SykmeldingsregisterRecordDto.toJson(): String = jacksonMapper().writeValueAsString(this)
