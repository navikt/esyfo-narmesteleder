package no.nav.syfo.narmestelederrelasjon.application

import kotlinx.coroutines.withTimeoutOrNull
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederrelasjon.domain.EmploymentEndedDecision
import no.nav.syfo.narmestelederrelasjon.domain.EmploymentEndedRule
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import kotlin.time.toKotlinDuration

// The reference app runs with TZ=Europe/Oslo and judges the rule on LocalDate.now().
private val NORWEGIAN_TIME: ZoneId = ZoneId.of("Europe/Oslo")

/** Shadow mode: records whether a relation would be revoked. It has no publisher, so it cannot revoke anything. */
class CheckNarmestelederrelasjonEmploymentUseCase(
    private val repository: EmploymentReconciliationRepository,
    private val employmentHistoryLookup: EmploymentHistoryLookup,
    private val settings: EmploymentCheckSettings,
    private val clock: Clock,
    private val metrics: EmploymentCheckMetrics,
) {
    suspend fun execute() {
        val claims = repository.claimDue(limit = settings.batchSize, lease = settings.lease, now = clock.instant())
        claims.forEach { claim -> metrics.recordCheck(check(claim)) }
    }

    private suspend fun check(claim: ClaimedEmploymentCheck): ClaimedCheckOutcome {
        val elapsed = Duration.between(claim.claimedAt, clock.instant()).toKotlinDuration()
        val lookupBudget = settings.lease - CLAIM_SAFETY_MARGIN - elapsed
        // Only this timeout is swallowed; cancellation of the loop propagates.
        val history = withTimeoutOrNull(lookupBudget) {
            employmentHistoryLookup.findEmploymentHistory(claim.employeeIdent)
        } ?: return ClaimedCheckOutcome.TIMEOUT

        val now = clock.instant()
        // A comparison judges the relation on the Norwegian date the source revoked it, even if we ingest or check it later.
        val outcome = decide(claim, history, today = LocalDate.ofInstant(claim.sourceRevokedAt ?: now, NORWEGIAN_TIME))
        if (!repository.complete(claim, outcome, nextCheck = nextCheck(outcome, now), now = now)) {
            // Another pod owns the row now and logs its own check.
            return ClaimedCheckOutcome.CLAIM_LOST
        }
        if (history is EmploymentHistoryResult.Unavailable) {
            logger.logEvent(employmentCheckFailed, claim.narmesteLederId, upstreamFailure = history.failure)
        }
        val checked = when (outcome) {
            EmploymentCheckOutcome.VALID -> ClaimedCheckOutcome.VALID
            EmploymentCheckOutcome.WOULD_REVOKE -> ClaimedCheckOutcome.WOULD_REVOKE
            EmploymentCheckOutcome.FAILED -> ClaimedCheckOutcome.FAILED
            EmploymentCheckOutcome.REVOKED -> error("Shadow mode never revokes")
        }
        if (claim.sourceRevokedAt != null) compare(claim, checked)
        return checked
    }

    /** Only for a relation team sykmelding revoked because the employment ended. A failed lookup is compared when it is retried. */
    private fun compare(claim: ClaimedEmploymentCheck, checked: ClaimedCheckOutcome) {
        when (checked) {
            ClaimedCheckOutcome.WOULD_REVOKE -> metrics.recordComparison(EmploymentComparisonResult.AGREE)
            ClaimedCheckOutcome.VALID -> {
                metrics.recordComparison(EmploymentComparisonResult.DISAGREE)
                logger.event(employmentCheckComparisonMismatch, claim.narmesteLederId)
            }
            ClaimedCheckOutcome.FAILED, ClaimedCheckOutcome.CLAIM_LOST, ClaimedCheckOutcome.TIMEOUT -> Unit
        }
    }

    private fun decide(
        claim: ClaimedEmploymentCheck,
        history: EmploymentHistoryResult,
        today: LocalDate,
    ): EmploymentCheckOutcome = when (history) {
        is EmploymentHistoryResult.Unavailable -> EmploymentCheckOutcome.FAILED
        is EmploymentHistoryResult.Found -> when (EmploymentEndedRule.evaluate(claim.organizationNumber, history.employments, today)) {
            EmploymentEndedDecision.KEEP -> EmploymentCheckOutcome.VALID
            EmploymentEndedDecision.REVOKE -> EmploymentCheckOutcome.WOULD_REVOKE
        }
    }

    /** A failed lookup is retried the next day; otherwise in a month, as team sykmelding does. */
    private fun nextCheck(outcome: EmploymentCheckOutcome, now: Instant): Instant = if (outcome == EmploymentCheckOutcome.FAILED) {
        now.plus(Duration.ofDays(1))
    } else {
        now.atOffset(UTC).plusMonths(1).toInstant()
    }

    companion object {
        private val logger = applicationLogger(CheckNarmestelederrelasjonEmploymentUseCase::class.java)
    }
}
