package no.nav.syfo.sykmelding.application

import no.nav.syfo.sykmelding.retention.domain.SykmeldingRetentionPolicy
import java.time.LocalDate
import java.util.UUID

/**
 * What a batch of sendt-sykmelding records should do to the database, decided without touching it.
 */
internal data class SykmeldingBatchPlan(
    val sykmeldingerToSave: List<SendtSykmelding>,
    val sykmeldingIdsToRevoke: List<UUID>,
    val uniqueKeyCount: Int,
) {
    companion object {
        fun from(records: List<SykmeldingRecord>, today: LocalDate): SykmeldingBatchPlan {
            val latestRecords = records.latestRecordPerSykmeldingId()
            val (tombstones, sendtRecords) = latestRecords.partition { it.message == null }

            return SykmeldingBatchPlan(
                sykmeldingerToSave = sendtRecords
                    .mapNotNull { it.message?.toValidSendtSykmelding(today) }
                    .latestTomPerArbeidsforhold(),
                sykmeldingIdsToRevoke = tombstones.map { it.sykmeldingId },
                uniqueKeyCount = latestRecords.size,
            )
        }
    }
}

/** The record with the highest offset is the final state of a sykmelding. */
private fun List<SykmeldingRecord>.latestRecordPerSykmeldingId(): List<SykmeldingRecord> = groupBy { it.sykmeldingId }
    .map { (_, recordsForSykmelding) -> recordsForSykmelding.maxBy { it.offset } }

/** Returns null when the sykmelding has no arbeidsgiver or its latest period is outside the retention period. */
private fun SendtSykmeldingKafkaMessage.toValidSendtSykmelding(today: LocalDate): SendtSykmelding? {
    val arbeidsgiver = event.arbeidsgiver ?: return null
    val latestPeriod = sykmelding.sykmeldingsperioder.maxBy { it.tom }

    if (!SykmeldingRetentionPolicy.shouldRetain(tom = latestPeriod.tom, today = today)) return null

    return SendtSykmelding(
        sykmeldingId = UUID.fromString(event.sykmeldingId),
        fnr = kafkaMetadata.fnr,
        orgnummer = arbeidsgiver.orgnummer,
        fom = latestPeriod.fom,
        tom = latestPeriod.tom,
        syketilfelleStartDato = sykmelding.syketilfelleStartDato,
    )
}

/**
 * Sykmeldinger can be sent to the arbeidsgiver out of order, so only the one with the latest tom
 * per arbeidsforhold (fnr + orgnummer) is kept.
 */
private fun List<SendtSykmelding>.latestTomPerArbeidsforhold(): List<SendtSykmelding> = groupBy { it.fnr to it.orgnummer }
    .map { (_, sykmeldinger) -> sykmeldinger.maxBy { it.tom } }
