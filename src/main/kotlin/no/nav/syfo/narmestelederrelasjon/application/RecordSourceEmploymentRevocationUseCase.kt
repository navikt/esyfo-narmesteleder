package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.logging.rethrowCancellation
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
 * Notes employment-ended revocations from the source, team sykmelding's `narmesteleder-arbeidsforhold` that the
 * shadow check is to replace (#473), so the shadow check can compare. Runs after the Leesah register commit and
 * never fails ingestion.
 */
class RecordSourceEmploymentRevocationUseCase(
    private val repository: EmploymentReconciliationRepository,
    private val clock: Clock,
    private val metrics: EmploymentCheckMetrics,
) {
    fun execute(narmesteLederId: UUID, revokedAt: Instant) {
        try {
            val now = clock.instant()
            val outcome = when {
                revokedAt < now.minus(SOURCE_OBSERVATION_WINDOW) -> SourceObservationOutcome.IGNORED_STALE
                repository.recordSourceRevocation(narmesteLederId, revokedAt) -> SourceObservationOutcome.RECORDED
                else -> SourceObservationOutcome.ALREADY_RECORDED
            }
            metrics.recordObservation(outcome)
        } catch (exception: Exception) {
            exception.rethrowCancellation()
            metrics.recordObservation(SourceObservationOutcome.FAILED)
            logger.logEvent(employmentCheckSourceObservationFailed, narmesteLederId, cause = exception)
        }
    }

    companion object {
        private val logger = applicationLogger(RecordSourceEmploymentRevocationUseCase::class.java)
    }
}
