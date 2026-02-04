package no.nav.syfo.sykmelding.model

import java.time.Instant
import java.util.UUID
import no.nav.syfo.sykmelding.db.SykmeldingEntity

data class SendtSykmeldingKafkaMessage(
    val sykmelding: ArbeidsgiverSykmelding,
    val kafkaMetadata: KafkaMetadata,
    val event: Event,
)

fun SendtSykmeldingKafkaMessage.toDbEntity(): SykmeldingEntity {
    val mostRecentSykmeldingperiode = sykmelding.sykmeldingsperioder.maxBy { it.tom }
    return SykmeldingEntity(
        sykmeldingId = UUID.fromString(kafkaMetadata.sykmeldingId),
        fnr = kafkaMetadata.fnr,
        orgnummer = requireNotNull(event.arbeidsgiver?.orgnummer) { "orgnummer must not be null in db entities" },
        fom = mostRecentSykmeldingperiode.fom,
        tom = mostRecentSykmeldingperiode.tom,
        syketilfelleStartDato = sykmelding.syketilfelleStartDato,
        created = Instant.now(),
        updated = Instant.now(),
    )
}
