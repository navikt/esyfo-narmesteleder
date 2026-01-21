package no.nav.syfo.sykmelding.db

import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class FakeSykmeldingDb : ISykmeldingDb {
    private val store = CopyOnWriteArrayList<SendtSykmeldingEntity>()

    override suspend fun insertSykmelding(sykmeldingEntity: SendtSykmeldingEntity) {
        store += sykmeldingEntity
    }

    override suspend fun revokeSykmelding(
        sykmeldingId: UUID,
        revokedDate: LocalDate
    ): Int {
        var affected = 0
        for (i in store.indices) {
            val current = store[i]
            if (current.sykmeldingId == sykmeldingId && !current.tom.isAfter(revokedDate)) {
                store[i] = current.copy(revokedDate = revokedDate)
                affected++
            }
        }
        return affected
    }

    override suspend fun findBySykmeldingId(sykmeldingId: UUID): List<SendtSykmeldingEntity> = store.filter { it.sykmeldingId == sykmeldingId }

    fun findAll(): List<SendtSykmeldingEntity> = store.toList()

    fun clear() {
        store.clear()
    }
}
