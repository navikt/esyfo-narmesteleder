package no.nav.syfo.sykmelding.application

import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.sykmelding.retention.domain.SykmeldingRetentionPolicy
import no.nav.syfo.util.logger
import org.slf4j.event.Level
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

private data class SykmeldingBatchProcessedDetails(
    val revokedCount: Int,
    val deletedDuplicateCount: Int,
    val recordCount: Int,
    val uniqueKeyCount: Int,
)

private val sykmeldingBatchProcessed = applicationEvent<SykmeldingBatchProcessedDetails>(
    name = "sykmelding_batch_processed",
    level = Level.INFO,
    message = "Sendt sykmelding batch processed",
    operation = "persist_sendt_sykmelding",
    fields = mapOf(
        "revoked_count" to { it.revokedCount },
        "deleted_duplicate_count" to { it.deletedDuplicateCount },
        "record_count" to { it.recordCount },
        "unique_key_count" to { it.uniqueKeyCount },
    ),
)

class SykmeldingService(
    private val sykmeldingDb: SykmeldingDb,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    suspend fun processBatch(records: List<SykmeldingRecord>) {
        if (records.isEmpty()) return

        // Dedupe to final state per sykmeldingId (highest offset wins)
        val finalStateByKey = records
            .groupBy { it.sykmeldingId }
            .mapValues { (_, recordsForKey) -> recordsForKey.maxBy { it.offset } }
            .values

        // Partition into inserts vs revokes based on final state
        val (revokes, inserts) = finalStateByKey.partition { it.message == null }

        val sykmeldingerToUpsert = inserts.mapNotNull { record ->
            record.message?.let { msg -> toSendtSykmeldingIfValid(msg) }
        }
            // When multiple sykmeldingIds map to the same fnr+orgnummer, keep only the highest tom date
            // (sykmeldinger could be sent to arbeidsgiver in the wrong order)
            .groupBy { sykmelding -> sykmelding.fnr to sykmelding.orgnummer }
            .mapValues { (_, sykmeldinger) -> sykmeldinger.maxBy { it.tom } }
            .values
            .toList()

        sykmeldingDb.transaction {
            // We may receive sykmeldinger with the same sykmeldingId
            // when a sykmelding is sent on paper and the veileder corrects something. This will not get
            // caught by the (fnr,orgnummer) constraint if the correction is a change of employer or fnr (uncertain if this could happen)
            val deletedDuplicateSykmeldingIds = batchDeleteAllBySykmeldingIds(sykmeldingerToUpsert.map { it.sykmeldingId })

            // Upsert: inserts new rows, or updates existing rows only when the incoming tom >= existing tom.
            // This is handled by the DB via ON CONFLICT (fnr, orgnummer) with a WHERE clause.
            batchUpsertSykmeldingerIfMoreRecentTom(sykmeldingerToUpsert)

            val revokedRows = batchRevokeSykmelding(revokes.map { it.sykmeldingId }, LocalDate.now())

            logger.logEvent(
                sykmeldingBatchProcessed,
                SykmeldingBatchProcessedDetails(
                    revokedCount = revokedRows,
                    deletedDuplicateCount = deletedDuplicateSykmeldingIds,
                    recordCount = records.size,
                    uniqueKeyCount = finalStateByKey.size,
                ),
            )
        }
    }

    private fun toSendtSykmeldingIfValid(message: SendtSykmeldingKafkaMessage): SendtSykmelding? {
        val arbeidsgiver = message.event.arbeidsgiver ?: return null
        val latestPeriod = message.sykmelding.sykmeldingsperioder.maxBy { it.tom }

        if (!latestPeriodIsWithinOneYear(latestPeriod)) return null

        return SendtSykmelding(
            sykmeldingId = UUID.fromString(message.event.sykmeldingId),
            fnr = message.kafkaMetadata.fnr,
            orgnummer = arbeidsgiver.orgnummer,
            fom = latestPeriod.fom,
            tom = latestPeriod.tom,
            syketilfelleStartDato = message.sykmelding.syketilfelleStartDato,
        )
    }

    private fun latestPeriodIsWithinOneYear(
        sykmeldingsperiodeAGDTO: SykmeldingsperiodeAGDTO
    ): Boolean = SykmeldingRetentionPolicy.shouldRetain(
        tom = sykmeldingsperiodeAGDTO.tom,
        today = LocalDate.now(clock),
    )

    companion object {
        private val logger = logger()
    }
}
