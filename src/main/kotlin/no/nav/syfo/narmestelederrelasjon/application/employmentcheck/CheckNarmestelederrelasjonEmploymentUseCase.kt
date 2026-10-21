package no.nav.syfo.narmestelederrelasjon.application.employmentcheck

import kotlinx.coroutines.withTimeoutOrNull
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocation
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.domain.EmploymentEndedDecision
import no.nav.syfo.narmestelederrelasjon.domain.EmploymentEndedRule
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.observability.rethrowCancellation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset.UTC
import kotlin.time.toKotlinDuration

// The reference app runs with TZ=Europe/Oslo and judges the rule on LocalDate.now().
private val NORWEGIAN_TIME: ZoneId = ZoneId.of("Europe/Oslo")

/** Checks employment and either records a shadow decision or publishes a revocation when live mode is enabled. */
class CheckNarmestelederrelasjonEmploymentUseCase(
    private val repository: EmploymentReconciliationRepository,
    private val employmentHistoryLookup: EmploymentHistoryLookup,
    private val settings: EmploymentCheckSettings,
    private val clock: Clock,
    private val metrics: EmploymentCheckMetrics,
    private val publisher: PublishNarmestelederrelasjonRevocation,
    private val revokeEnabled: Boolean,
) {
    suspend fun execute() {
        val claims = repository.claimDue(limit = settings.batchSize, lease = settings.lease, now = clock.instant(), revokeEnabled = revokeEnabled)
        claims.forEach { claim -> metrics.recordCheck(check(claim)) }
    }

    private suspend fun check(claim: ClaimedEmploymentCheck): ClaimedCheckOutcome {
        val elapsed = Duration.between(claim.claimedAt, clock.instant()).toKotlinDuration()
        val lookupBudget = settings.lease - CLAIM_SAFETY_MARGIN - elapsed
        // Only this timeout is swallowed; cancellation of the loop propagates.
        val history = withTimeoutOrNull(lookupBudget) {
            employmentHistoryLookup.findEmploymentHistory(claim.employeeIdent)
        } ?: return ClaimedCheckOutcome.TIMEOUT

        var now = clock.instant()
        // A comparison judges the relation on the Norwegian date the source revoked it, even if we ingest or check it later.
        val ruleInstant = if (revokeEnabled) now else claim.sourceRevokedAt ?: now
        var outcome = decide(claim, history, today = LocalDate.ofInstant(ruleInstant, NORWEGIAN_TIME))
        if (outcome == EmploymentCheckOutcome.REVOKED) {
            val publishElapsed = Duration.between(claim.claimedAt, now).toKotlinDuration()
            if (publishElapsed + publisher.maxPublishDuration >= settings.lease - CLAIM_SAFETY_MARGIN) return ClaimedCheckOutcome.TIMEOUT
            if (!repository.isClaimStillValid(claim, now)) return ClaimedCheckOutcome.STALE
            try {
                publisher.publish(
                    PublishNarmestelederrelasjonRevocationCommand(claim.employeeIdent, claim.organizationNumber, RevocationInitiator.EMPLOYMENT_ENDED),
                )
            } catch (ex: Exception) {
                ex.rethrowCancellation()
                outcome = EmploymentCheckOutcome.FAILED
                logger.logEvent(employmentCheckRevocationFailed, claim.narmesteLederId, cause = ex)
            }
            now = clock.instant()
        }
        if (!repository.complete(claim, outcome, nextCheck = nextCheck(outcome, now), now = now)) {
            if (outcome == EmploymentCheckOutcome.REVOKED) logger.event(employmentCheckRevocationClaimLost, claim.narmesteLederId)
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
            EmploymentCheckOutcome.REVOKED -> ClaimedCheckOutcome.REVOKED
        }
        if (!revokeEnabled && claim.sourceRevokedAt != null) compare(claim, checked)
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
            ClaimedCheckOutcome.FAILED, ClaimedCheckOutcome.CLAIM_LOST, ClaimedCheckOutcome.TIMEOUT,
            ClaimedCheckOutcome.REVOKED, ClaimedCheckOutcome.STALE,
            -> Unit
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
            EmploymentEndedDecision.REVOKE -> if (revokeEnabled) EmploymentCheckOutcome.REVOKED else EmploymentCheckOutcome.WOULD_REVOKE
        }
    }

    /** A failed lookup or publish is retried the next day; otherwise in a month, as team sykmelding does. */
    private fun nextCheck(outcome: EmploymentCheckOutcome, now: Instant): Instant = if (outcome == EmploymentCheckOutcome.FAILED) {
        now.plus(Duration.ofDays(1))
    } else {
        now.atOffset(UTC).plusMonths(1).toInstant()
    }

    companion object {
        private val logger = applicationLogger(CheckNarmestelederrelasjonEmploymentUseCase::class.java)
    }
}
