package no.nav.syfo.sykmelding.db

import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class FakeSykmeldingDb : ISykmeldingDb {
    private val store = CopyOnWriteArrayList<SendtSykmeldingEntity>()

    override suspend fun insertSykmelding(sykmeldingEntity: SendtSykmeldingEntity) {
        val existingIndex = store.indexOfFirst { it.sykmeldingId == sykmeldingEntity.sykmeldingId }
        if (existingIndex >= 0) {
            // Upsert: update existing entry with new values
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

    override suspend fun revokeSykmelding(
        sykmeldingId: UUID,
        revokedDate: LocalDate
    ): Int {
        val index = store.indexOfFirst { it.sykmeldingId == sykmeldingId && !it.tom.isAfter(revokedDate) }
        if (index >= 0) {
            store[index] = store[index].copy(revokedDate = revokedDate)
            return 1
        }
        return 0
    }

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): SendtSykmeldingEntity? = store.find { it.sykmeldingId == sykmeldingId }

    fun findAll(): List<SendtSykmeldingEntity> = store.toList()

    fun clear() {
        store.clear()
    }
}
