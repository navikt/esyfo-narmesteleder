package no.nav.syfo.narmestelederbehov.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogLifecycleRepository
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialogReference
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

class ExposedNarmestelederbehovDialogLifecycleRepository(private val database: Database) : NarmestelederbehovDialogLifecycleRepository {
    override suspend fun findFulfilledDialogs(createdBefore: Instant): List<NarmestelederbehovDialogReference> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.select(NarmestelederbehovTable.id, NarmestelederbehovTable.dialogId)
                .where {
                    (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_FULFILLED) and
                        (NarmestelederbehovTable.created less createdBefore.atOffset(ZoneOffset.UTC)) and
                        NarmestelederbehovTable.dialogId.isNotNull()
                }
                .orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
                .limit(100)
                .map { NarmestelederbehovDialogReference(NarmestelederbehovId(it[NarmestelederbehovTable.id]), requireNotNull(it[NarmestelederbehovTable.dialogId])) }
        }
    }

    override suspend fun findDialogsToExpire(): List<NarmestelederbehovDialogReference> = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) {
            NarmestelederbehovTable.select(NarmestelederbehovTable.id, NarmestelederbehovTable.dialogId)
                .where {
                    (NarmestelederbehovTable.behovStatus inList listOf(BehovStatus.BEHOV_EXPIRED, BehovStatus.ERROR)) and
                        NarmestelederbehovTable.expiredInDialogporten.isNull() and
                        NarmestelederbehovTable.dialogId.isNotNull()
                }
                .orderBy(NarmestelederbehovTable.created to SortOrder.ASC)
                .limit(100)
                .map { NarmestelederbehovDialogReference(NarmestelederbehovId(it[NarmestelederbehovTable.id]), requireNotNull(it[NarmestelederbehovTable.dialogId])) }
        }
    }

    override suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant) {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq id.value }) {
                    it[expiredInDialogporten] = expirationTime.atOffset(ZoneOffset.UTC)
                }
            }
        }
    }
}
