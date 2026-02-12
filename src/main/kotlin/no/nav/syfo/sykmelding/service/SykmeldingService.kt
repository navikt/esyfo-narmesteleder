package no.nav.syfo.sykmelding.service

import no.nav.syfo.sykmelding.db.ISykmeldingDb
import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
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
            record.message?.let { toEntityIfValid(it) }
        }

        // Every sykmelding has its own id. We only need the most recent dates for a person in a given org, so
        // we may delete the old sykmelding (if any) and insert the new one.
        val sykmeldingerToLookUp = entitiesToInsert.map { it.fnr to it.orgnummer }

        val existingSykmeldingIds = if (entitiesToInsert.isNotEmpty()) {
            sykmeldingDb.findSykmeldingIdsByFnrAndOrgnr(sykmeldingerToLookUp)
        } else {
            emptyList()
        }

        sykmeldingDb.transaction {
            if (existingSykmeldingIds.isNotEmpty()) {
                deleteAll(existingSykmeldingIds)
            }
            if (entitiesToInsert.isNotEmpty()) {
                insertOrUpdateSykmeldingBatch(entitiesToInsert)
            }
            if (revokes.isNotEmpty()) {
                val revokeIds = revokes.map { it.sykmeldingId }
                revokeSykmeldingBatch(revokeIds, LocalDate.now())
            }
        }

        logger.info(
            "Batch processed: ${entitiesToInsert.size} inserts/updates, ${revokes.size} revokes ,${existingSykmeldingIds.size} deletes " +
                "(from ${records.size} total records, ${finalStateByKey.size} unique keys)"
        )
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
