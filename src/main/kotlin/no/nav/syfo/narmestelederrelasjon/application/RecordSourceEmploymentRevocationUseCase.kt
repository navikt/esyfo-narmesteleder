package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.observability.rethrowCancellation
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Older revocations are ignored, so a Leesah replay does not affect the comparison. A comparison is also given up this
 * long after the source's revocation, so a revocation ingested after six days has about one day left for the comparison
 * and any retries.
 */
internal val SOURCE_OBSERVATION_WINDOW: Duration = Duration.ofDays(7)

/**
 * Notes team sykmelding's employment-ended revocations so the shadow check can compare with them (#473).
 * Runs after the Leesah register commit and never fails ingestion.
 */
class RecordSourceEmploymentRevocationUseCase(
    private val repository: EmploymentReconciliationRepository,
    private val clock: Clock,
    private val metrics: EmploymentCheckMetrics,
) {
    fun execute(narmesteLederId: UUID, revokedAt: Instant) {
        val outcome = if (revokedAt < clock.instant().minus(SOURCE_OBSERVATION_WINDOW)) {
            SourceObservationOutcome.IGNORED_STALE
        } else {
            try {
                if (repository.recordSourceRevocation(narmesteLederId, revokedAt)) SourceObservationOutcome.RECORDED else SourceObservationOutcome.ALREADY_RECORDED
            } catch (exception: Exception) {
                // Database errors stay exceptions (ADR-0004); here they must not stop Leesah ingestion.
                exception.rethrowCancellation()
                logger.logEvent(employmentCheckSourceObservationFailed, narmesteLederId, cause = exception)
                SourceObservationOutcome.FAILED
            }
        }
        metrics.recordObservation(outcome)
    }

    companion object {
        private val logger = applicationLogger(RecordSourceEmploymentRevocationUseCase::class.java)
    }
}
