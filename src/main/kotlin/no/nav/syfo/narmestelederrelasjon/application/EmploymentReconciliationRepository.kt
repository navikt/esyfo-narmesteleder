package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

interface EmploymentReconciliationRepository {
    /** Seeds active relations only, with first due one calendar month after aktiv_fom. */
    suspend fun seedMissing(limit: Int, now: Instant): Int

    /** Claims due active relations, including expired leases, and commits before returning. */
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
}

data class ClaimedEmploymentCheck(
    val narmesteLederId: UUID,
    val organizationNumber: OrganizationNumber,
    val employeeIdent: PersonIdent,
    val claimToken: UUID,
    val claimedAt: Instant,
) {
    override fun toString(): String = "ClaimedEmploymentCheck(narmesteLederId=$narmesteLederId, claimToken=$claimToken, claimedAt=$claimedAt)"
}

enum class EmploymentCheckOutcome {
    GYLDIG,
    VILLE_BRUTT,
    BRUTT,
    FEILET,
}
