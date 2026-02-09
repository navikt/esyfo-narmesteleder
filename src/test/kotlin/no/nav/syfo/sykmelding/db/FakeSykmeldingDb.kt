package no.nav.syfo.sykmelding.db

import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class FakeSykmeldingDb : ISykmeldingDb {
    private val store = CopyOnWriteArrayList<SendtSykmeldingEntity>()

    private fun insertOrUpdateSykmelding(sykmeldingEntity: SendtSykmeldingEntity) {
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
        override fun insertOrUpdateSykmeldingBatch(entities: List<SendtSykmeldingEntity>) {
            entities.forEach { insertOrUpdateSykmelding(it) }
        }

        override fun revokeSykmeldingBatch(sykmeldingIds: List<UUID>, revokedDate: LocalDate): Int = sykmeldingIds.sumOf { revokeSykmelding(it, revokedDate) }

        override fun deleteAll(ids: List<UUID>) {
            ids.forEach { id -> store.removeIf { it.sykmeldingId == id } }
        }
    }

    override suspend fun transaction(block: suspend ISykmeldingTransaction.() -> Unit) {
        FakeTransaction().block()
    }

    override suspend fun findSykmeldingIdsByFnrAndOrgnr(map: List<Pair<String, String>>): List<UUID> = store.filter { entity -> map.any { (fnr, orgnr) -> entity.fnr == fnr && entity.orgnummer == orgnr } }
        .map { it.sykmeldingId }

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity? = store.find { it.sykmeldingId == sykmeldingId }

    fun findAll(): List<SendtSykmeldingEntity> = store.toList()

    fun clear() {
        store.clear()
    }
}
