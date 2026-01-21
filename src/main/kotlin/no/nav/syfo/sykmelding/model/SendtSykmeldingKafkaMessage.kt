package no.nav.syfo.sykmelding.model

import java.time.Instant
import java.util.UUID

data class SendtSykmeldingKafkaMessage(
    val sykmelding: ArbeidsgiverSykmelding,
    val kafkaMetadata: KafkaMetadata,
    val event: Event,
)

fun SendtSykmeldingKafkaMessage.toDto(): SendtSykmeldingDto {
    val mostRecentSykmeldingperiode = sykmelding.sykmeldingsperioder.maxBy { it.tom }
    return SendtSykmeldingDto(
        sykmeldingId = UUID.fromString(kafkaMetadata.sykmeldingId),
        fnr = kafkaMetadata.fnr,
        orgnummer = event.arbeidsgiver?.orgnummer,
        fom = mostRecentSykmeldingperiode.fom,
        tom = mostRecentSykmeldingperiode.tom,
        syketilfelleStartDato = sykmelding.syketilfelleStartDato,
        created = Instant.now(),
        updated = Instant.now(),
    )
}
