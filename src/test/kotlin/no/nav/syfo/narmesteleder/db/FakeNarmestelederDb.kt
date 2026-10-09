package no.nav.syfo.narmesteleder.db

import no.nav.syfo.narmesteleder.domain.BehovStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FakeNarmestelederDb : NarmestelederDb {
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
            dialogId = nlBehov.dialogId,
            fornavn = nlBehov.fornavn,
            mellomnavn = nlBehov.mellomnavn,
            etternavn = nlBehov.etternavn,
            dialogDeletePerformed = nlBehov.dialogDeletePerformed,
            expiredInDialogporten = nlBehov.expiredInDialogporten,
        )
        store[id] = toStore
    }

    override suspend fun markDialogCreated(
        id: UUID,
        dialogId: UUID,
        fornavn: String?,
        mellomnavn: String?,
        etternavn: String?,
    ): Boolean {
        val existing = store[id]?.takeIf { it.behovStatus == BehovStatus.BEHOV_CREATED } ?: return false
        store[id] = existing.copy(
            behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
            dialogId = dialogId,
            fornavn = fornavn,
            mellomnavn = mellomnavn,
            etternavn = etternavn,
        )
        return true
    }

    override suspend fun getNlBehovByStatus(status: BehovStatus, limit: Int): List<NarmestelederBehovEntity> = getNlBehovByStatus(listOf(status), limit)

    fun findBehovById(id: UUID): NarmestelederBehovEntity? = store[id]
    override suspend fun findBehovByParameters(sykmeldtFnr: String, orgnummer: String, behovStatus: List<BehovStatus>): List<NarmestelederBehovEntity> = store.values.filter {
        it.orgnummer == orgnummer &&
            it.sykmeldtFnr == sykmeldtFnr &&
            behovStatus.contains(it.behovStatus)
    }

    override suspend fun getNlBehovByStatus(status: List<BehovStatus>, limit: Int): List<NarmestelederBehovEntity> = store.values.filter { it.behovStatus in status }

    fun lastId(): UUID? = order.lastOrNull()
    fun findAll(): List<NarmestelederBehovEntity> = order.mapNotNull { store[it] }
    fun clear() {
        store.clear()
        order.clear()
    }
    override suspend fun getNlBehovForExpireInDialogporten(
        limit: Int,
        status: List<BehovStatus>
    ): List<NarmestelederBehovEntity> {
        val statusList = status.toList()
        return store.values.filter { it.behovStatus in statusList && it.expiredInDialogporten == null && it.dialogId != null }
            .sortedBy { it.created }
            .take(limit)
    }
}
