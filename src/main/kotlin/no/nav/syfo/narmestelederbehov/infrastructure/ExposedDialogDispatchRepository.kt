package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.DialogClaim
import no.nav.syfo.narmestelederbehov.application.DialogDispatchRepository
import no.nav.syfo.narmestelederbehov.application.DialogDispatchResult
import no.nav.syfo.narmestelederbehov.application.DialogDispatchState
import no.nav.syfo.narmestelederbehov.application.DialogDispatchStats
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.LiteralOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundOr
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class ExposedDialogDispatchRepository(private val database: Database) : DialogDispatchRepository {
    override suspend fun stats(): DialogDispatchStats = database.ioTransaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
        val count = DialogDispatchTable.behovId.count()
        val oldestPendingSince = DialogDispatchTable.pendingSince.min()
        val byStatus = DialogDispatchTable
            .select(DialogDispatchTable.status, count, oldestPendingSince)
            .where { statusIn(pendingStatuses + DialogDispatchStatus.PARKED) }
            .groupBy(DialogDispatchTable.status)
            .associateBy { it[DialogDispatchTable.status] }
        val pending = pendingStatuses.mapNotNull { byStatus[it] }
        DialogDispatchStats(
            pending = pending.sumOf { it[count] },
            parked = byStatus[DialogDispatchStatus.PARKED]?.get(count) ?: 0,
            oldestPending = pending.mapNotNull { it[oldestPendingSince] }.minOrNull()?.toInstant(),
        )
    }

    override suspend fun claim(now: Instant, lease: Duration): DialogClaim? {
        require(lease.isPositive() && lease.isFinite())
        return database.ioTransaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val row = DialogDispatchTable.select(DialogDispatchTable.behovId, DialogDispatchTable.revision, DialogDispatchTable.attempts).where {
                statusIn(pendingStatuses) and (DialogDispatchTable.availableAt lessEq now.atOffset(UTC))
            }.orderBy(DialogDispatchTable.availableAt to SortOrder.ASC, DialogDispatchTable.behovId to SortOrder.ASC)
                .limit(1)
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .singleOrNull() ?: return@ioTransaction null
            val claim = DialogClaim(
                NarmestelederbehovId(row[DialogDispatchTable.behovId]),
                UUID.randomUUID(),
                row[DialogDispatchTable.revision],
                now.plus(lease.toJavaDuration()),
                row[DialogDispatchTable.attempts],
            )
            DialogDispatchTable.update({ DialogDispatchTable.behovId eq claim.id.value }) {
                it[status] = DialogDispatchStatus.CLAIMED
                it[claimToken] = claim.token
                it[availableAt] = claim.leaseUntil.atOffset(UTC)
            }
            claim
        }
    }

    override suspend fun load(claim: DialogClaim, now: Instant): DialogDispatchState? = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        val row = NarmestelederbehovTable.select(
            NarmestelederbehovTable.sykmeldtFnr,
            NarmestelederbehovTable.orgnummer,
            NarmestelederbehovTable.behovStatus,
            NarmestelederbehovTable.dialogId,
            NarmestelederbehovTable.expiredInDialogporten,
        ).where { NarmestelederbehovTable.id eq claim.id.value }.singleOrNull() ?: return@ioTransaction null
        // Read dispatch state after behov: an unchanged revision proves that the behov snapshot is current.
        val control = DialogDispatchTable.select(DialogDispatchTable.revision, DialogDispatchTable.createStarted, DialogDispatchTable.intendedExpiry)
            .where { owns(claim, now) }
            .singleOrNull() ?: return@ioTransaction null
        if (control[DialogDispatchTable.revision] != claim.revision) return@ioTransaction null
        DialogDispatchState(
            Employee(PersonalIdentificationNumber(row[NarmestelederbehovTable.sykmeldtFnr]), OrganizationNumber(row[NarmestelederbehovTable.orgnummer])),
            row[NarmestelederbehovTable.behovStatus],
            row[NarmestelederbehovTable.dialogId],
            control[DialogDispatchTable.createStarted],
            control[DialogDispatchTable.intendedExpiry]?.toInstant(),
            row[NarmestelederbehovTable.expiredInDialogporten]?.toInstant(),
        )
    }

    override suspend fun beginCreation(claim: DialogClaim, now: Instant): Boolean = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        DialogDispatchTable.update({ owns(claim, now) and (DialogDispatchTable.revision eq claim.revision) }) {
            it[createStarted] = true
        } == 1
    }

    override suspend fun prepareExpiry(claim: DialogClaim, expiry: Instant, now: Instant): Boolean = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        DialogDispatchTable.update({ owns(claim, now) and (DialogDispatchTable.revision eq claim.revision) }) {
            it[intendedExpiry] = expiry.atOffset(UTC)
        } == 1
    }

    override suspend fun saveCreated(claim: DialogClaim, dialogId: UUID, name: BehovPersonName?, now: Instant): Boolean = mutateBehov(claim, now) {
        NarmestelederbehovTable.update({ NarmestelederbehovTable.id eq claim.id.value }) {
            it[NarmestelederbehovTable.dialogId] = dialogId
            it[fornavn] = name?.firstName
            it[mellomnavn] = name?.middleName
            it[etternavn] = name?.lastName
        }
        NarmestelederbehovTable.update({
            (NarmestelederbehovTable.id eq claim.id.value) and (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_CREATED)
        }) {
            it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
        }
        scheduleDialogDispatch(claim.id.value)
    }

    override suspend fun saveCompleted(claim: DialogClaim, now: Instant): Boolean = mutateBehov(claim, now) {
        val updated = NarmestelederbehovTable.update({
            (NarmestelederbehovTable.id eq claim.id.value) and (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_FULFILLED)
        }) {
            it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
        }
        if (updated == 1) scheduleDialogDispatch(claim.id.value)
    }

    override suspend fun saveExpired(claim: DialogClaim, expiry: Instant, now: Instant): Boolean = mutateBehov(claim, now) {
        NarmestelederbehovTable.update({
            (NarmestelederbehovTable.id eq claim.id.value) and
                (NarmestelederbehovTable.behovStatus inList listOf(BehovStatus.BEHOV_EXPIRED, BehovStatus.ERROR))
        }) {
            it[expiredInDialogporten] = expiry.atOffset(UTC)
        }
    }

    // Domain writers lock behov before dispatch state; use the same order.
    private suspend fun mutateBehov(claim: DialogClaim, now: Instant, mutation: JdbcTransaction.() -> Unit): Boolean = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        NarmestelederbehovTable.select(NarmestelederbehovTable.id).where { NarmestelederbehovTable.id eq claim.id.value }.forUpdate().single()
        if (DialogDispatchTable.select(DialogDispatchTable.behovId).where { owns(claim, now) }.forUpdate().empty()) return@ioTransaction false
        mutation()
        true
    }

    override suspend fun finish(claim: DialogClaim, result: DialogDispatchResult, now: Instant): Boolean = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        val row = DialogDispatchTable.select(DialogDispatchTable.revision).where { owns(claim, now) }.forUpdate().singleOrNull() ?: return@ioTransaction false
        val next = nextDispatchState(superseded = row[DialogDispatchTable.revision] != claim.revision, result, claim.attempts, now)
        DialogDispatchTable.update({ owns(claim, now) }) {
            it[claimToken] = null
            it[status] = next.status
            it[availableAt] = next.availableAt.atOffset(UTC)
            it[attempts] = next.attempts
            it[lastFailure] = next.lastFailure
            if (next.clearPendingSince) it[pendingSince] = null
        } == 1
    }

    private fun owns(claim: DialogClaim, now: Instant): Op<Boolean> = (DialogDispatchTable.behovId eq claim.id.value) and
        (DialogDispatchTable.claimToken eq claim.token) and
        (DialogDispatchTable.status eq DialogDispatchStatus.CLAIMED) and
        (DialogDispatchTable.availableAt greater now.atOffset(UTC))
}

private val pendingStatuses = listOf(DialogDispatchStatus.READY, DialogDispatchStatus.CLAIMED)

// Fixed literals keep the partial indexes usable with generic prepared plans.
private fun statusIn(statuses: List<DialogDispatchStatus>): Op<Boolean> = statuses.map { DialogDispatchTable.status eq LiteralOp(DialogDispatchTable.status.columnType, it) }.compoundOr()

private data class NextDispatchState(
    val status: DialogDispatchStatus,
    val availableAt: Instant,
    val attempts: Int,
    val lastFailure: String? = null,
    val clearPendingSince: Boolean = false,
)

private fun nextDispatchState(superseded: Boolean, result: DialogDispatchResult, attempts: Int, now: Instant): NextDispatchState = if (superseded) {
    NextDispatchState(DialogDispatchStatus.READY, now, attempts = 0)
} else {
    when (result) {
        DialogDispatchResult.Done -> NextDispatchState(DialogDispatchStatus.IDLE, now, attempts = 0, clearPendingSince = true)
        is DialogDispatchResult.Retry -> NextDispatchState(DialogDispatchStatus.READY, result.nextAttempt, attempts + 1, result.reason)
        is DialogDispatchResult.Parked -> NextDispatchState(DialogDispatchStatus.PARKED, now, attempts + 1, result.reason)
    }
}
