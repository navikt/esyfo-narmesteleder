package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.ClaimedEmploymentCheck
import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckOutcome
import no.nav.syfo.narmestelederrelasjon.application.EmploymentReconciliationRepository
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.Connection
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * All transactions use READ COMMITTED explicitly. The pool default is REPEATABLE READ, where
 * `FOR UPDATE SKIP LOCKED` on rows changed by another pod gives serialization failures instead
 * of re-checking the row. Do not remove it.
 */
class ExposedEmploymentReconciliationRepository(
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EmploymentReconciliationRepository {
    override suspend fun seedMissing(limit: Int, now: Instant): Int {
        require(limit > 0) { "limit must be greater than zero" }
        return withContext(dispatcher) {
            suspendTransaction(db = database, transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                // Each target column is paired with its value so the INSERT and SELECT lists cannot drift apart.
                val values: List<Pair<Column<*>, Expression<*>>> = listOf(
                    EmploymentReconciliationTable.narmestelederId to NarmestelederTable.narmestelederId,
                    EmploymentReconciliationTable.status to
                        QueryParameter(EmploymentCheckStatus.READY, EmploymentReconciliationTable.status.columnType),
                    EmploymentReconciliationTable.nextCheckAt to firstCheck,
                    EmploymentReconciliationTable.created to
                        QueryParameter(now.atOffset(ZoneOffset.UTC), EmploymentReconciliationTable.created.columnType),
                )
                val missing = NarmestelederTable.join(
                    otherTable = EmploymentReconciliationTable,
                    joinType = JoinType.LEFT,
                    onColumn = NarmestelederTable.narmestelederId,
                    otherColumn = EmploymentReconciliationTable.narmestelederId,
                ).select(values.map { it.second }).where {
                    NarmestelederTable.aktivTom.isNull() and EmploymentReconciliationTable.narmestelederId.isNull()
                }.orderBy(NarmestelederTable.narmestelederId to SortOrder.ASC).limit(limit)

                EmploymentReconciliationTable.insertIgnore(missing, columns = values.map { it.first }) ?: 0
            }
        }
    }

    override suspend fun claimDue(limit: Int, lease: Duration, now: Instant): List<ClaimedEmploymentCheck> {
        require(limit > 0) { "limit must be greater than zero" }
        require(lease.isPositive() && lease.isFinite()) { "lease must be positive and finite" }
        val leaseUntil = now.plus(lease.toJavaDuration()).atOffset(ZoneOffset.UTC)
        return withContext(dispatcher) {
            suspendTransaction(db = database, transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val token = UUID.randomUUID()
                val claimed = EmploymentReconciliationTable.join(
                    otherTable = NarmestelederTable,
                    joinType = JoinType.INNER,
                    onColumn = EmploymentReconciliationTable.narmestelederId,
                    otherColumn = NarmestelederTable.narmestelederId,
                ).select(
                    EmploymentReconciliationTable.narmestelederId,
                    NarmestelederTable.orgnummer,
                    NarmestelederTable.sykmeldtFnr,
                ).where {
                    NarmestelederTable.aktivTom.isNull() and
                        (
                            (EmploymentReconciliationTable.status eq EmploymentCheckStatus.READY) or
                                (EmploymentReconciliationTable.status eq EmploymentCheckStatus.CLAIMED)
                            ) and
                        (EmploymentReconciliationTable.nextCheckAt lessEq now.atOffset(ZoneOffset.UTC))
                }.orderBy(
                    EmploymentReconciliationTable.nextCheckAt to SortOrder.ASC,
                    EmploymentReconciliationTable.narmestelederId to SortOrder.ASC,
                ).limit(limit)
                    // Lock only the control rows, so Leesah upserts on narmeste_leder neither block nor get skipped.
                    .forUpdate(
                        ForUpdateOption.PostgreSQL.ForUpdate(
                            ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED,
                            EmploymentReconciliationTable,
                        ),
                    ).map {
                        ClaimedEmploymentCheck(
                            narmesteLederId = it[EmploymentReconciliationTable.narmestelederId],
                            organizationNumber = OrganizationNumber(it[NarmestelederTable.orgnummer]),
                            employeeIdent = PersonIdent(it[NarmestelederTable.sykmeldtFnr]),
                            claimToken = token,
                            claimedAt = now,
                        )
                    }
                if (claimed.isNotEmpty()) {
                    // The control rows are still locked; no external work occurs in this transaction.
                    EmploymentReconciliationTable.update({
                        EmploymentReconciliationTable.narmestelederId inList claimed.map { it.narmesteLederId }
                    }) {
                        it[status] = EmploymentCheckStatus.CLAIMED
                        it[claimToken] = token
                        it[nextCheckAt] = leaseUntil
                    }
                }
                // suspendTransaction commits before this list leaves the repository.
                claimed
            }
        }
    }

    override suspend fun complete(
        claim: ClaimedEmploymentCheck,
        outcome: EmploymentCheckOutcome,
        nextCheck: Instant,
        now: Instant,
    ): Boolean = withContext(dispatcher) {
        suspendTransaction(db = database, transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            EmploymentReconciliationTable.update({
                (EmploymentReconciliationTable.narmestelederId eq claim.narmesteLederId) and
                    (EmploymentReconciliationTable.status eq EmploymentCheckStatus.CLAIMED) and
                    (EmploymentReconciliationTable.claimToken eq claim.claimToken)
            }) {
                it[status] = EmploymentCheckStatus.READY
                it[claimToken] = null
                it[lastCheckedAt] = now.atOffset(ZoneOffset.UTC)
                it[lastOutcome] = outcome
                it[nextCheckAt] = nextCheck.atOffset(ZoneOffset.UTC)
                if (outcome == EmploymentCheckOutcome.WOULD_REVOKE) {
                    it[shadowWouldRevokeAt] = Coalesce(
                        shadowWouldRevokeAt,
                        QueryParameter(now.atOffset(ZoneOffset.UTC), shadowWouldRevokeAt.columnType),
                    )
                }
            } > 0
        }
    }

    override suspend fun isClaimStillValid(claim: ClaimedEmploymentCheck, now: Instant): Boolean = withContext(dispatcher) {
        suspendTransaction(db = database, transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            !EmploymentReconciliationTable.join(
                otherTable = NarmestelederTable,
                joinType = JoinType.INNER,
                onColumn = EmploymentReconciliationTable.narmestelederId,
                otherColumn = NarmestelederTable.narmestelederId,
            ).select(EmploymentReconciliationTable.narmestelederId).where {
                (EmploymentReconciliationTable.narmestelederId eq claim.narmesteLederId) and
                    (EmploymentReconciliationTable.status eq EmploymentCheckStatus.CLAIMED) and
                    (EmploymentReconciliationTable.claimToken eq claim.claimToken) and
                    (EmploymentReconciliationTable.nextCheckAt greater now.atOffset(ZoneOffset.UTC)) and
                    (NarmestelederTable.orgnummer eq claim.organizationNumber.value) and
                    (NarmestelederTable.sykmeldtFnr eq claim.employeeIdent.value) and
                    NarmestelederTable.aktivTom.isNull()
            }.empty()
        }
    }
}

// Add a calendar month in UTC, independent of the connection's session TimeZone.
// The interval and zone are constants; all runtime inputs use DSL parameters.
private val firstCheck = object : Expression<OffsetDateTime>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("((").append(NarmestelederTable.aktivFom)
            .append(" at time zone 'UTC') + interval '1 month') at time zone 'UTC'")
    }
}
