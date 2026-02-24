package no.nav.syfo.sykmelding.db

import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class FakeSykmeldingDb : ISykmeldingDb {
    private val store = CopyOnWriteArrayList<SendtSykmeldingEntity>()

    private fun upsertSykmelding(entity: SendtSykmeldingEntity): Int {
        val existingIndex = store.indexOfFirst { it.fnr == entity.fnr && it.orgnummer == entity.orgnummer }
        if (existingIndex >= 0) {
            val existing = store[existingIndex]
            if (entity.tom >= existing.tom) {
                store[existingIndex] = existing.copy(
                    sykmeldingId = entity.sykmeldingId,
                    syketilfelleStartDato = entity.syketilfelleStartDato,
                    fom = entity.fom,
                    tom = entity.tom,
                    updated = entity.updated,
                    revokedDate = null
                )
            }
        } else {
            store += entity
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
        override fun batchUpsertSykmeldingerIfMoreRecentTom(entities: List<SendtSykmeldingEntity>): Int {
            var count = 0
            entities.forEach { upsertSykmelding(it).also { count += it } }
            return count
        }

        override fun batchRevokeSykmelding(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int = sykmeldingIds.sumOf { revokeSykmelding(it, revokedDate) }
        override fun batchDeleteAllBySykmeldingIds(sykmeldingIds: List<UUID>): Int {
            var count = 0
            sykmeldingIds.forEach { id ->
                val removed = store.removeIf { it.sykmeldingId == id }
                if (removed) count++
            }
            return count
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
