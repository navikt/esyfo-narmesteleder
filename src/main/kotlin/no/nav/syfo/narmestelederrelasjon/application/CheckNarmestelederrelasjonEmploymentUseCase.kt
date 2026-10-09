package no.nav.syfo.narmestelederrelasjon.application

import kotlinx.coroutines.withTimeoutOrNull
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederrelasjon.domain.EmploymentEndedDecision
import no.nav.syfo.narmestelederrelasjon.domain.EmploymentEndedRule
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset.UTC
import kotlin.time.toKotlinDuration

data class CheckBatchResult(
    val valid: Int = 0,
    val wouldRevoke: Int = 0,
    val failed: Int = 0,
    val claimLost: Int = 0,
    val timeout: Int = 0,
)

/** Shadow only: this action has no publisher dependency and cannot revoke a relation. */
class CheckNarmestelederrelasjonEmploymentUseCase(
    private val repository: EmploymentReconciliationRepository,
    private val employmentHistoryLookup: EmploymentHistoryLookup,
    private val settings: EmploymentCheckSettings,
    private val clock: Clock,
    private val metrics: EmploymentCheckMetrics,
) {
    suspend fun execute(): CheckBatchResult {
        val claims = repository.claimDue(limit = settings.batchSize, lease = settings.lease, now = clock.instant())
        val counts = CheckOutcome.entries.associateWith { 0 }.toMutableMap()
        for (claim in claims) {
            val elapsed = Duration.between(claim.claimedAt, clock.instant()).toKotlinDuration()
            val remaining = settings.lease - elapsed - CLAIM_SAFETY_MARGIN
            // Only this timeout is swallowed; loop cancellation propagates.
            val outcome = withTimeoutOrNull(remaining) { check(claim) } ?: CheckOutcome.TIMEOUT
            counts[outcome] = counts.getValue(outcome) + 1
            metrics.countCheck(outcome)
        }
        return CheckBatchResult(
            valid = counts.getValue(CheckOutcome.VALID),
            wouldRevoke = counts.getValue(CheckOutcome.WOULD_REVOKE),
            failed = counts.getValue(CheckOutcome.FAILED),
            claimLost = counts.getValue(CheckOutcome.CLAIM_LOST),
            timeout = counts.getValue(CheckOutcome.TIMEOUT),
        ).also {
            if (claims.isNotEmpty()) logger.event(employmentCheckBatchCompleted, it)
        }
    }

    private suspend fun check(claim: ClaimedEmploymentCheck): CheckOutcome {
        val history = employmentHistoryLookup.findEmploymentHistory(claim.employeeIdent)
        val now = clock.instant()
        val outcome = when (history) {
            is EmploymentHistoryResult.Found -> when (
                EmploymentEndedRule.evaluate(claim.organizationNumber, history.employments, LocalDate.ofInstant(now, UTC))
            ) {
                EmploymentEndedDecision.KEEP -> EmploymentCheckOutcome.VALID
                EmploymentEndedDecision.REVOKE -> EmploymentCheckOutcome.WOULD_REVOKE
            }
            is EmploymentHistoryResult.Unavailable -> EmploymentCheckOutcome.FAILED
        }
        val nextCheck = if (outcome == EmploymentCheckOutcome.FAILED) {
            now.plus(Duration.ofDays(1))
        } else {
            now.atOffset(UTC).plusMonths(1).toInstant()
        }
        if (!repository.complete(claim, outcome, nextCheck = nextCheck, now = now)) return CheckOutcome.CLAIM_LOST
        if (history is EmploymentHistoryResult.Unavailable) {
            logger.logEvent(employmentCheckFailed, claim.narmesteLederId, upstreamFailure = history.failure)
        }
        if (claim.sourceRevocationObservedAt != null) compare(claim, outcome)
        return when (outcome) {
            EmploymentCheckOutcome.VALID -> CheckOutcome.VALID
            EmploymentCheckOutcome.WOULD_REVOKE -> CheckOutcome.WOULD_REVOKE
            EmploymentCheckOutcome.FAILED -> CheckOutcome.FAILED
            EmploymentCheckOutcome.REVOKED -> error("Shadow mode must not publish")
        }
    }

    private fun compare(claim: ClaimedEmploymentCheck, outcome: EmploymentCheckOutcome): Unit = when (outcome) {
        EmploymentCheckOutcome.WOULD_REVOKE -> metrics.countComparison(EmploymentComparisonResult.AGREE)
        EmploymentCheckOutcome.VALID -> {
            metrics.countComparison(EmploymentComparisonResult.DISAGREE)
            logger.event(employmentCheckComparisonMismatch, claim.narmesteLederId)
        }
        EmploymentCheckOutcome.FAILED -> Unit
        EmploymentCheckOutcome.REVOKED -> error("Shadow mode must not publish")
    }

    companion object {
        private val logger = applicationLogger(CheckNarmestelederrelasjonEmploymentUseCase::class.java)
    }
}
