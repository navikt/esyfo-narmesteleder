package no.nav.syfo.sykmelding.db

import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.also

class FakeSykmeldingDb : ISykmeldingDb {
    private val store = CopyOnWriteArrayList<SendtSykmeldingEntity>()

    private fun insertOrUpdateSykmelding(sykmeldingEntity: SendtSykmeldingEntity): Int {
        val existingIndex = store.indexOfFirst { it.sykmeldingId == sykmeldingEntity.sykmeldingId }
        if (existingIndex >= 0) {
            val existing = store[existingIndex]
            store[existingIndex] = existing.copy(
                fnr = sykmeldingEntity.fnr,
                fom = sykmeldingEntity.fom,
                tom = sykmeldingEntity.tom,
                updated = sykmeldingEntity.updated
            )
        } else {
            store += sykmeldingEntity
        }
        return 1
    }

    private fun revokeSykmelding(sykmeldingId: UUID, revokedDate: LocalDate): Int {
        val index = store.indexOfFirst { it.sykmeldingId == sykmeldingId && !it.tom.isAfter(revokedDate) }
        if (index >= 0) {
            store[index] = store[index].copy(revokedDate = revokedDate)
            return 1
        }
        return 0
    }

    private inner class FakeTransaction : ISykmeldingTransaction {
        override fun insertSykmeldingBatch(entities: List<SendtSykmeldingEntity>): Int {
            var removedCount = 0
            entities.forEach { insertOrUpdateSykmelding(it).also { removedCount += it } }
            return removedCount
        }

        override fun revokeSykmeldingBatch(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int = sykmeldingIds.sumOf { revokeSykmelding(it, revokedDate) }

        override fun deleteAllByFnrAndOrgnr(toDelete: List<Pair<String, String>>): Int {
            var removedCount = 0
            toDelete.forEach { (fnr, orgnummer) -> store.removeIf { it.fnr == fnr && it.orgnummer == orgnummer }.also { if (it) removedCount += 1 } }
            return removedCount
        }
    }

    override suspend fun transaction(block: suspend ISykmeldingTransaction.() -> Unit) {
        FakeTransaction().block()
    }

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity? = store.find { it.sykmeldingId == sykmeldingId }

    fun findAll(): List<SendtSykmeldingEntity> = store.toList()

    fun clear() {
        store.clear()
    }
}
