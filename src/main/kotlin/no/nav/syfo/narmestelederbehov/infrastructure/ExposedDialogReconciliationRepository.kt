package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.DialogClaim
import no.nav.syfo.narmestelederbehov.application.DialogReconciliationRepository
import no.nav.syfo.narmestelederbehov.application.DialogReconciliationResult
import no.nav.syfo.narmestelederbehov.application.DialogReconciliationState
import no.nav.syfo.narmestelederbehov.application.DialogReconciliationStats
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.database.ioTransaction
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class ExposedDialogReconciliationRepository(private val database: Database) : DialogReconciliationRepository {
    override suspend fun stats(): DialogReconciliationStats = database.ioTransaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
        requireNotNull(
            exec(
                """
            SELECT count(*) FILTER (WHERE status IN ('READY', 'CLAIMED')),
                   count(*) FILTER (WHERE status = 'PARKED'),
                   min(pending_since) FILTER (WHERE status IN ('READY', 'CLAIMED'))
            FROM nl_dialog_reconciliation
            WHERE status IN ('READY', 'CLAIMED', 'PARKED')
                """.trimIndent(),
            ) { rows ->
                rows.next()
                DialogReconciliationStats(rows.getLong(1), rows.getLong(2), rows.getTimestamp(3)?.toInstant())
            }
        )
    }

    override suspend fun claim(now: Instant, lease: Duration): DialogClaim? {
        require(lease.isPositive() && lease.isFinite())
        return database.ioTransaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            val row = DialogReconciliationTable.selectAll().where {
                // Fixed literals keep the partial index usable with generic prepared plans.
                (
                    (DialogReconciliationTable.status eq stringLiteral("READY")) or
                        (DialogReconciliationTable.status eq stringLiteral("CLAIMED"))
                    ) and
                    (DialogReconciliationTable.availableAt lessEq now.atOffset(UTC))
            }.orderBy(DialogReconciliationTable.availableAt to SortOrder.ASC, DialogReconciliationTable.behovId to SortOrder.ASC)
                .limit(1)
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .singleOrNull() ?: return@ioTransaction null
            val claim = DialogClaim(
                NarmestelederbehovId(row[DialogReconciliationTable.behovId]),
                UUID.randomUUID(),
                row[DialogReconciliationTable.revision],
                now.plus(lease.toJavaDuration()),
                row[DialogReconciliationTable.attempts],
            )
            DialogReconciliationTable.update({ DialogReconciliationTable.behovId eq claim.id.value }) {
                it[status] = "CLAIMED"
                it[claimToken] = claim.token
                it[availableAt] = claim.leaseUntil.atOffset(UTC)
            }
            claim
        }
    }

    override suspend fun load(claim: DialogClaim, now: Instant): DialogReconciliationState? = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        if (DialogReconciliationTable.selectAll().where { owns(claim, now) }.empty()) return@ioTransaction null
        val row = NarmestelederbehovTable.selectAll().where { NarmestelederbehovTable.id eq claim.id.value }.single()
        val control = DialogReconciliationTable.selectAll().where { owns(claim, now) }.singleOrNull() ?: return@ioTransaction null
        if (control[DialogReconciliationTable.revision] != claim.revision) return@ioTransaction null
        DialogReconciliationState(
            Employee(PersonalIdentificationNumber(row[NarmestelederbehovTable.sykmeldtFnr]), OrganizationNumber(row[NarmestelederbehovTable.orgnummer])),
            row[NarmestelederbehovTable.behovStatus],
            row[NarmestelederbehovTable.dialogId],
            control[DialogReconciliationTable.createStarted],
            control[DialogReconciliationTable.intendedExpiry]?.toInstant(),
            row[NarmestelederbehovTable.expiredInDialogporten]?.toInstant(),
        )
    }

    override suspend fun beginCreation(claim: DialogClaim, now: Instant): Boolean = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        DialogReconciliationTable.update({ owns(claim, now) and (DialogReconciliationTable.revision eq claim.revision) }) {
            it[createStarted] = true
        } == 1
    }

    override suspend fun prepareExpiry(claim: DialogClaim, expiry: Instant, now: Instant): Boolean = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        DialogReconciliationTable.update({ owns(claim, now) and (DialogReconciliationTable.revision eq claim.revision) }) {
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
    }

    override suspend fun saveCompleted(claim: DialogClaim, now: Instant): Boolean = mutateBehov(claim, now) {
        NarmestelederbehovTable.update({
            (NarmestelederbehovTable.id eq claim.id.value) and (NarmestelederbehovTable.behovStatus eq BehovStatus.BEHOV_FULFILLED)
        }) {
            it[behovStatus] = BehovStatus.DIALOGPORTEN_STATUS_SET_COMPLETED
        }
    }

    override suspend fun saveExpired(claim: DialogClaim, expiry: Instant, now: Instant): Boolean = mutateBehov(claim, now) {
        NarmestelederbehovTable.update({
            (NarmestelederbehovTable.id eq claim.id.value) and
                (NarmestelederbehovTable.behovStatus inList listOf(BehovStatus.BEHOV_EXPIRED, BehovStatus.ERROR))
        }) {
            it[expiredInDialogporten] = expiry.atOffset(UTC)
        }
    }

    // Domain writers lock behov before the scheduling trigger locks control; use the same order.
    private suspend fun mutateBehov(claim: DialogClaim, now: Instant, mutation: () -> Unit): Boolean = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        NarmestelederbehovTable.selectAll().where { NarmestelederbehovTable.id eq claim.id.value }.forUpdate().single()
        if (DialogReconciliationTable.selectAll().where { owns(claim, now) }.forUpdate().empty()) return@ioTransaction false
        mutation()
        true
    }

    override suspend fun finish(claim: DialogClaim, result: DialogReconciliationResult, now: Instant): Boolean = database.ioTransaction(
        transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
    ) {
        val row = DialogReconciliationTable.selectAll().where { owns(claim, now) }.forUpdate().singleOrNull() ?: return@ioTransaction false
        val changed = row[DialogReconciliationTable.revision] != claim.revision
        DialogReconciliationTable.update({ owns(claim, now) }) {
            it[claimToken] = null
            it[status] = when {
                changed -> "READY"
                result is DialogReconciliationResult.Done -> "IDLE"
                result is DialogReconciliationResult.Parked -> "PARKED"
                else -> "READY"
            }
            it[availableAt] = if (!changed && result is DialogReconciliationResult.Retry) result.nextAttempt.atOffset(UTC) else now.atOffset(UTC)
            it[attempts] = if (changed || result == DialogReconciliationResult.Done) 0 else claim.attempts + 1
            it[lastFailure] = if (changed) {
                null
            } else {
                when (result) {
                    DialogReconciliationResult.Done -> null
                    is DialogReconciliationResult.Parked -> result.reason
                    is DialogReconciliationResult.Retry -> result.reason
                }
            }
            if (!changed && result == DialogReconciliationResult.Done) it[pendingSince] = null
        } == 1
    }

    private fun owns(claim: DialogClaim, now: Instant): Op<Boolean> = (DialogReconciliationTable.behovId eq claim.id.value) and
        (DialogReconciliationTable.claimToken eq claim.token) and
        (DialogReconciliationTable.status eq "CLAIMED") and
        (DialogReconciliationTable.availableAt greater now.atOffset(UTC))
}
