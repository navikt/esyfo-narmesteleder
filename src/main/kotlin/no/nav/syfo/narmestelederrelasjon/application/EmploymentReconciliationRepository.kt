package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/**
 * Queue of periodic employment checks, one per active narmestelederrelasjon (#473).
 * Every pod may claim work: a claim takes a time-limited lease and a new claim token, and
 * all later writes compare-and-set on that token (ADR-0003).
 */
interface EmploymentReconciliationRepository {
    /** Seeds active relations only, with first due one calendar month after aktiv_fom. */
    suspend fun seedMissing(limit: Int, now: Instant): Int

    /**
     * Claims due active relations or pending recent source comparisons, including expired leases.
     * Commits before returning.
     */
    suspend fun claimDue(limit: Int, lease: Duration, now: Instant): List<ClaimedEmploymentCheck>

    /** Returns false without writing when status or token no longer matches this claim. */
    suspend fun complete(
        claim: ClaimedEmploymentCheck,
        outcome: EmploymentCheckOutcome,
        nextCheck: Instant,
        now: Instant,
    ): Boolean

    /** Checks the active relation still matches the claimed employee and organization; not a lock over external effects. */
    suspend fun isClaimStillValid(claim: ClaimedEmploymentCheck, now: Instant): Boolean

    /** Blocking. Marks the first observation and makes READY rows due without changing a CLAIMED lease. */
    fun recordSourceRevocation(narmesteLederId: UUID, observedAt: Instant, now: Instant): Boolean

    /** Aggregate comparison and backlog gauges; no relation identifiers leave this query. */
    suspend fun comparisonStats(now: Instant): EmploymentCheckStats
}

data class EmploymentCheckStats(
    val onlyShadowLt31d: Long,
    val onlyShadowGte31d: Long,
    val due: Long,
    val firstSweepRemaining: Long,
)

data class ClaimedEmploymentCheck(
    val narmesteLederId: UUID,
    val organizationNumber: OrganizationNumber,
    val employeeIdent: PersonIdent,
    val claimToken: UUID,
    val claimedAt: Instant,
    /** Pending, recent source observation at claim time; null after a successful comparison check. */
    val sourceRevocationObservedAt: Instant? = null,
) {
    override fun toString(): String = "ClaimedEmploymentCheck(narmesteLederId=$narmesteLederId, claimToken=$claimToken, claimedAt=$claimedAt)"
}

enum class EmploymentCheckOutcome {
    /** The employee still has qualifying employment in the organization. */
    VALID,

    /** Shadow mode decided the relation would be revoked, but nothing was published. */
    WOULD_REVOKE,

    /** The revocation was published. */
    REVOKED,

    /** The lookup failed; the relation is never revoked on failure. */
    FAILED,
}
