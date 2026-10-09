package no.nav.syfo.narmestelederrelasjon.application

import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

internal class RecordingEmploymentCheckMetrics : EmploymentCheckMetrics {
    val checks = mutableListOf<CheckOutcome>()
    val comparisons = mutableListOf<EmploymentComparisonResult>()
    val observations = mutableListOf<SourceObservationOutcome>()
    val stats = mutableListOf<EmploymentCheckStats>()

    override fun countCheck(outcome: CheckOutcome) {
        checks += outcome
    }

    override fun countComparison(result: EmploymentComparisonResult) {
        comparisons += result
    }

    override fun countObservation(outcome: SourceObservationOutcome) {
        observations += outcome
    }

    override fun refresh(stats: EmploymentCheckStats) {
        this.stats += stats
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
    var claimArguments: Triple<Int, Duration, Instant>? = null
    val observations = mutableListOf<Triple<UUID, Instant, Instant>>()
    private val recordedIds = mutableSetOf<UUID>()
    var seedResults = listOf(0)
    var seedCalls = 0
    var statsCalls = 0
    var stats = EmploymentCheckStats(0, 0, 0, 0)

    override suspend fun claimDue(limit: Int, lease: Duration, now: Instant): List<ClaimedEmploymentCheck> {
        claimArguments = Triple(limit, lease, now)
        return claims
    }

    override suspend fun complete(
        claim: ClaimedEmploymentCheck,
        outcome: EmploymentCheckOutcome,
        nextCheck: Instant,
        now: Instant,
    ): Boolean {
        completed += RecordedCompletion(claim, outcome, nextCheck, now)
        return completeSucceeds
    }

    override fun recordSourceRevocation(narmesteLederId: UUID, observedAt: Instant, now: Instant): Boolean {
        observations += Triple(narmesteLederId, observedAt, now)
        return recordedIds.add(narmesteLederId)
    }

    override suspend fun seedMissing(limit: Int, now: Instant): Int = seedResults[seedCalls++]

    override suspend fun comparisonStats(now: Instant): EmploymentCheckStats {
        statsCalls++
        return stats
    }

    override suspend fun isClaimStillValid(claim: ClaimedEmploymentCheck, now: Instant): Boolean = error("Not used")
}
