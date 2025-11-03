package no.nav.syfo.narmesteleder.db

import java.util.*
import java.util.concurrent.ConcurrentHashMap

class FakeNarmestelederDb : INarmestelederDb {
    private val store = ConcurrentHashMap<UUID, NarmestelederBehovEntity>()
    private val order = mutableListOf<UUID>()

    override suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): UUID {
        val id = nlBehov.id ?: UUID.randomUUID()
        store[id] = nlBehov.copy(id = id)
        order += id
        return id
    }

    override suspend fun updateNlBehov(nlBehov: NarmestelederBehovEntity) {
        val id = nlBehov.id ?: error("Cannot update entity without id")
        val existing = store[id] ?: return
        val toStore = existing.copy(
            orgnummer = nlBehov.orgnummer,
            hovedenhetOrgnummer = nlBehov.hovedenhetOrgnummer,
            sykmeldtFnr = nlBehov.sykmeldtFnr,
            narmestelederFnr = nlBehov.narmestelederFnr,
            behovStatus = nlBehov.behovStatus,
        )
        store[id] = toStore
    }

    override suspend fun findBehovById(id: UUID): NarmestelederBehovEntity? = store[id]

    fun lastId(): UUID? = order.lastOrNull()
    fun findAll(): List<NarmestelederBehovEntity> = order.mapNotNull { store[it] }
    fun clear() {
        store.clear(); order.clear()
    }
}
