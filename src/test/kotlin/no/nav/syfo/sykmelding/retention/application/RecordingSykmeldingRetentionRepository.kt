package no.nav.syfo.sykmelding.retention.application

import java.time.LocalDate

class RecordingSykmeldingRetentionRepository(vararg deletedPerBatch: Int) : SykmeldingRetentionRepository {
    private val deletedPerBatch = ArrayDeque(deletedPerBatch.toList())
    val requests = mutableListOf<Pair<LocalDate, Int>>()

    override suspend fun deleteBefore(cutoff: LocalDate, batchSize: Int): Int {
        requests += cutoff to batchSize
        return deletedPerBatch.removeFirstOrNull() ?: 0
    }
}
