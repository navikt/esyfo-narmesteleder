package no.nav.syfo.sykmelding.service

import no.nav.syfo.sykmelding.db.ISykmeldingDb
import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.db.fnrToOrgnummerPair
import no.nav.syfo.sykmelding.kafka.SykmeldingRecord
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.model.SykmeldingsperiodeAGDTO
import no.nav.syfo.util.logger
import java.time.LocalDate
import java.util.UUID

class SykmeldingService(
    private val sykmeldingDb: ISykmeldingDb,
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

        val entitiesToInsert = inserts.mapNotNull { record ->
            record.message?.let { msg -> toEntityIfValid(msg)?.let { entity -> record.offset to entity } }
        }
            // When multiple sykmeldingIds map to the same fnr+orgnummer, keep only the highest offset
            .groupBy { (_, entity) -> entity.fnr to entity.orgnummer }
            .mapValues { (_, entries) -> entries.maxBy { (offset, _) -> offset }.second }
            .values
            .toList()

        sykmeldingDb.transaction {
            // Every sykmelding has its own id. We only need the most recent dates for a person in a given org, so
            // we may delete the old sykmelding (if any) and insert the new one.
            val (deletedRows, insertedRows) = if (entitiesToInsert.isNotEmpty()) {
                Pair(
                    deleteAllByFnrAndOrgnr(entitiesToInsert.map(SendtSykmeldingEntity::fnrToOrgnummerPair)),
                    insertSykmeldingBatch(entitiesToInsert)
                )
            } else {
                Pair(0, 0)
            }

            val revokes = if (revokes.isNotEmpty()) {
                val revokeIds = revokes.map { it.sykmeldingId }
                revokeSykmeldingBatch(revokeIds, LocalDate.now())
            } else {
                0
            }

            logger.info(
                "Batch processed: $insertedRows inserts/updates, $revokes revokes, $deletedRows deletes " +
                    "(from ${records.size} total records, ${finalStateByKey.size} unique keys)"
            )
        }
    }

    private fun toEntityIfValid(message: SendtSykmeldingKafkaMessage): SendtSykmeldingEntity? {
        val arbeidsgiver = message.event.arbeidsgiver ?: return null
        val latestPeriod = message.sykmelding.sykmeldingsperioder.maxBy { it.tom }

        if (!latestPeriodIsWithinOneYear(latestPeriod)) return null

        return SendtSykmeldingEntity(
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
    ): Boolean = sykmeldingsperiodeAGDTO.tom >= LocalDate.now().minusYears(1)

    companion object {
        private val logger = logger()
    }
}
