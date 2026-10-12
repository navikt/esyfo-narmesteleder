package no.nav.syfo.narmestelederrelasjon.application

import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

internal class RecordingEmploymentCheckMetrics : EmploymentCheckMetrics {
    val checks = mutableListOf<ClaimedCheckOutcome>()
    val comparisons = mutableListOf<EmploymentComparisonResult>()
    val observations = mutableListOf<SourceObservationOutcome>()
    val refreshed = mutableListOf<EmploymentCheckStats>()

    override fun recordCheck(outcome: ClaimedCheckOutcome) {
        checks += outcome
    }

    override fun recordComparison(result: EmploymentComparisonResult) {
        comparisons += result
    }

    override fun recordObservation(outcome: SourceObservationOutcome) {
        observations += outcome
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
    val completed = mutableListOf<RecordedCompletion>()
    val observed = mutableMapOf<UUID, Instant>()
    var seeded = 0
    var stats = EmploymentCheckStats(onlyShadowUnder31Days = 0, onlyShadow31DaysOrMore = 0, due = 0)

    override suspend fun seedMissing(limit: Int, now: Instant): Int = seeded

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

    override suspend fun isClaimStillValid(claim: ClaimedEmploymentCheck, now: Instant): Boolean = error("Not used in shadow mode")

    override fun recordSourceRevocation(narmesteLederId: UUID, observedAt: Instant): Boolean = observed.putIfAbsent(narmesteLederId, observedAt) == null

    override suspend fun stats(now: Instant): EmploymentCheckStats = stats
}
