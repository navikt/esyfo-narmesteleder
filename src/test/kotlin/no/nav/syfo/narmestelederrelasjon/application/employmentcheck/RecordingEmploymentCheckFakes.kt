package no.nav.syfo.narmestelederrelasjon.application.employmentcheck

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

internal class RecordingEmploymentCheckMetrics : EmploymentCheckMetrics {
    val checks = mutableListOf<ClaimedCheckOutcome>()
    val refreshed = mutableListOf<EmploymentCheckStats>()

    override fun recordCheck(outcome: ClaimedCheckOutcome) {
        checks += outcome
    }

    override fun refresh(stats: EmploymentCheckStats) {
        refreshed += stats
    }
}

internal data class RecordedCompletion(
    val claim: ClaimedEmploymentCheck,
    val outcome: EmploymentCheckOutcome,
    val nextCheck: Instant,
    val now: Instant,
)

internal class RecordingEmploymentReconciliationRepository : EmploymentReconciliationRepository {
    var claims = emptyList<ClaimedEmploymentCheck>()
    var completeSucceeds = true
    var claimStillValid = true
    val validated = mutableListOf<ClaimedEmploymentCheck>()
    val completed = mutableListOf<RecordedCompletion>()
    var seeded = 0
    val seedRequests = CopyOnWriteArrayList<Pair<Int, Instant>>()
    var stats = EmploymentCheckStats(due = 0)

    override suspend fun seedMissing(limit: Int, now: Instant): Int {
        seedRequests += limit to now
        return seeded
    }

    override suspend fun claimDue(limit: Int, lease: Duration, now: Instant): List<ClaimedEmploymentCheck> = claims

    override suspend fun complete(
        claim: ClaimedEmploymentCheck,
        outcome: EmploymentCheckOutcome,
        nextCheck: Instant,
        now: Instant,
    ): Boolean {
        completed += RecordedCompletion(claim, outcome, nextCheck, now)
        return completeSucceeds
    }

    override suspend fun isClaimStillValid(claim: ClaimedEmploymentCheck, now: Instant): Boolean {
        validated += claim
        return claimStillValid
    }

    override suspend fun stats(now: Instant): EmploymentCheckStats = stats
}
