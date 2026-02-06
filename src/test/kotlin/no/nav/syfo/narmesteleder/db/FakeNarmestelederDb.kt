package no.nav.syfo.narmesteleder.db

import no.nav.syfo.narmesteleder.domain.BehovStatus
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class FakeNarmestelederDb : INarmestelederDb {
    private val store = ConcurrentHashMap<UUID, NarmestelederBehovEntity>()
    private val order = mutableListOf<UUID>()

    override suspend fun insertNlBehov(nlBehov: NarmestelederBehovEntity): NarmestelederBehovEntity {
        val persist = nlBehov.copy(id = UUID.randomUUID())
        store[persist.id!!] = persist
        order += persist.id
        return persist
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

    override suspend fun getNlBehovByStatus(status: BehovStatus, limit: Int): List<NarmestelederBehovEntity> = store.values.filter { it.behovStatus == status }.take(limit)

    override suspend fun getNlBehovForResendToDialogporten(status: BehovStatus, limit: Int): List<NarmestelederBehovEntity> = store.values.filter { it.behovStatus == status && it.dialogDeletePerformed != null && it.dialogId == null }.take(limit)
    override suspend fun getNlBehovForDelete(limit: Int): List<NarmestelederBehovEntity> = store.values.filter { it.dialogDeletePerformed == null }
        .sortedBy { it.created }
        .take(limit)

    override suspend fun findBehovById(id: UUID): NarmestelederBehovEntity? = store[id]
    override suspend fun findBehovByParameters(sykmeldtFnr: String, orgnummer: String, behovStatus: List<BehovStatus>): List<NarmestelederBehovEntity> = store.values.filter {
        it.orgnummer == orgnummer &&
            it.sykmeldtFnr == sykmeldtFnr &&
            behovStatus.contains(it.behovStatus)
    }

    override suspend fun findBehovByParameters(
        orgNumber: String,
        createdAfter: Instant,
        status: List<BehovStatus>,
        limit: Int
    ): List<NarmestelederBehovEntity> = store.values.filter {
        it.orgnummer == orgNumber &&
            it.created.isAfter(createdAfter) &&
            it.created.isBefore(Instant.now()) &&
            status.contains(it.behovStatus)
    }.take(limit)

    fun lastId(): UUID? = order.lastOrNull()
    fun findAll(): List<NarmestelederBehovEntity> = order.mapNotNull { store[it] }
    fun clear() {
        store.clear()
        order.clear()
    }
}
