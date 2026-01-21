package no.nav.syfo.sykmelding.db

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class FakeSykmeldingDb : ISykmeldingDb {
    private val store = ConcurrentHashMap<Long, List<SykmeldingEntity>>()

    override suspend fun insertSykmelding(sykmeldingEntity: SykmeldingEntity) {
        val hydratedSykmeldingEntity = sykmeldingEntity.copy(
            id = sykmeldingEntity.id ?: Random.nextLong(),
        )
        requireNotNull(hydratedSykmeldingEntity.id)

        store.merge(
            hydratedSykmeldingEntity.id,
            listOf(hydratedSykmeldingEntity)
        ) { existingList, newList ->
            existingList + newList
        }
    }

    override suspend fun deleteSykmelding(sykmeldingId: UUID): Int {
        var entries = 0

        store.entries.removeIf { (_, sykmeldingEntities) ->
            entries = sykmeldingEntities.count { it.sykmeldingId == sykmeldingId }
            entries > 0
        }
        return entries
    }

    fun clear() {
        store.clear()
    }
}
