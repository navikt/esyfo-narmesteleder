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
import org.jetbrains.exposed.v1.core.stringLiteral
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
                    EmploymentReconciliationTable.status to stringLiteral("KLAR"),
                    EmploymentReconciliationTable.nesteKontroll to firstCheck,
                    EmploymentReconciliationTable.opprettet to
                        QueryParameter(now.atOffset(ZoneOffset.UTC), EmploymentReconciliationTable.opprettet.columnType),
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
                        ((EmploymentReconciliationTable.status eq "KLAR") or (EmploymentReconciliationTable.status eq "CLAIMED")) and
                        (EmploymentReconciliationTable.nesteKontroll lessEq now.atOffset(ZoneOffset.UTC))
                }.orderBy(
                    EmploymentReconciliationTable.nesteKontroll to SortOrder.ASC,
                    EmploymentReconciliationTable.narmestelederId to SortOrder.ASC,
                ).limit(limit)
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
                        it[status] = "CLAIMED"
                        it[claimToken] = token
                        it[nesteKontroll] = leaseUntil
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
                    (EmploymentReconciliationTable.status eq "CLAIMED") and
                    (EmploymentReconciliationTable.claimToken eq claim.claimToken)
            }) {
                it[status] = "KLAR"
                it[claimToken] = null
                it[sistKontrollert] = now.atOffset(ZoneOffset.UTC)
                it[sistUtfall] = outcome.name
                it[nesteKontroll] = nextCheck.atOffset(ZoneOffset.UTC)
                if (outcome == EmploymentCheckOutcome.VILLE_BRUTT) {
                    it[skyggeVilleBrutt] = Coalesce(
                        skyggeVilleBrutt,
                        QueryParameter(now.atOffset(ZoneOffset.UTC), skyggeVilleBrutt.columnType),
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
                    (EmploymentReconciliationTable.status eq "CLAIMED") and
                    (EmploymentReconciliationTable.claimToken eq claim.claimToken) and
                    (EmploymentReconciliationTable.nesteKontroll greater now.atOffset(ZoneOffset.UTC)) and
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
