package no.nav.syfo.narmestelederrelasjon.application.employmentcheck

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

    /** Claims due active relations, including expired leases and legacy WOULD_REVOKE rows. Commits before returning. */
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

    /** Counts active rows whose scheduled time or lease has elapsed, for the backlog gauge. */
    suspend fun stats(now: Instant): EmploymentCheckStats
}

data class EmploymentCheckStats(val due: Long)

data class ClaimedEmploymentCheck(
    val narmesteLederId: UUID,
    val organizationNumber: OrganizationNumber,
    val employeeIdent: PersonalIdentificationNumber,
    val claimToken: UUID,
    val claimedAt: Instant,
) {
    override fun toString(): String = "ClaimedEmploymentCheck(narmesteLederId=$narmesteLederId, claimToken=$claimToken, claimedAt=$claimedAt)"
}

enum class EmploymentCheckOutcome {
    /** The employee still has qualifying employment in the organization. */
    VALID,

    /** The revocation was published. */
    REVOKED,

    /** The lookup or publish failed; a failed lookup never revokes the relation. */
    FAILED,
}
