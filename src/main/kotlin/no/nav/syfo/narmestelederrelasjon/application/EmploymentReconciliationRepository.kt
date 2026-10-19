package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
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
     * Claims due active relations, including expired leases. Shadow mode also claims pending source comparisons;
     * live mode makes READY WOULD_REVOKE rows immediately due at cutover. Commits before returning.
     */
    suspend fun claimDue(limit: Int, lease: Duration, now: Instant, revokeEnabled: Boolean = false): List<ClaimedEmploymentCheck>

    /** Returns false without writing when status or token no longer matches this claim. */
    suspend fun complete(
        claim: ClaimedEmploymentCheck,
        outcome: EmploymentCheckOutcome,
        nextCheck: Instant,
        now: Instant,
    ): Boolean

    /** Checks the active relation still matches the claimed employee and organization; not a lock over external effects. */
    suspend fun isClaimStillValid(claim: ClaimedEmploymentCheck, now: Instant): Boolean

    /**
     * Blocking, for the Leesah consumer. Notes when team sykmelding revoked the relation (Leesah `timestamp`) and makes a READY row
     * due ahead of other checks; a CLAIMED row keeps its lease. Returns false when it was already noted.
     */
    fun recordSourceRevocation(narmesteLederId: UUID, revokedAt: Instant): Boolean

    /** Counts over all active relations, for gauges. */
    suspend fun stats(now: Instant): EmploymentCheckStats
}

/**
 * Counts over active relations. `onlyShadow*` are relations the shadow check would revoke but team sykmelding has
 * not; after 31 days team sykmelding has checked them too, since it checks each relation about once a month.
 * [due] is the backlog; it is near zero once the first sweep is done.
 */
data class EmploymentCheckStats(
    val onlyShadowUnder31Days: Long,
    val onlyShadow31DaysOrMore: Long,
    val due: Long,
)

data class ClaimedEmploymentCheck(
    val narmesteLederId: UUID,
    val organizationNumber: OrganizationNumber,
    val employeeIdent: PersonalIdentificationNumber,
    val claimToken: UUID,
    val claimedAt: Instant,
    /** When team sykmelding revoked the relation this check is compared with; null for a regular check. */
    val sourceRevokedAt: Instant? = null,
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

    /** The lookup or publish failed; a failed lookup never revokes the relation. */
    FAILED,
}
