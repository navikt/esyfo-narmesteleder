package no.nav.syfo.sykmelding.application

import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.observability.logger
import org.slf4j.event.Level
import java.time.Clock
import java.time.LocalDate

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

        val today = LocalDate.now(clock)
        val plan = SykmeldingBatchPlan.from(records, today)

        sykmeldingDb.transaction {
            val replacedCount = saveLatestPerArbeidsforhold(plan.sykmeldingerToSave)
            val revokedCount = revoke(plan.sykmeldingIdsToRevoke, revokedDate = today)

            logger.logEvent(
                sykmeldingBatchProcessed,
                SykmeldingBatchProcessedDetails(
                    revokedCount = revokedCount,
                    deletedDuplicateCount = replacedCount,
                    recordCount = records.size,
                    uniqueKeyCount = plan.uniqueKeyCount,
                ),
            )
        }
    }

    companion object {
        private val logger = logger()
    }
}
