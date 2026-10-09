package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.logging.rethrowCancellation
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Blocking observation after the Leesah register commit. */
class RecordSourceEmploymentRevocationUseCase(
    private val repository: EmploymentReconciliationRepository,
    private val clock: Clock,
    private val metrics: EmploymentCheckMetrics,
) {
    fun execute(narmesteLederId: UUID, observedAt: Instant) {
        try {
            val now = clock.instant()
            val outcome = when {
                observedAt < now.minus(SOURCE_OBSERVATION_WINDOW) -> SourceObservationOutcome.IGNORED_STALE
                repository.recordSourceRevocation(narmesteLederId, observedAt, now = now) -> SourceObservationOutcome.RECORDED
                else -> SourceObservationOutcome.ALREADY_RECORDED
            }
            metrics.countObservation(outcome)
        } catch (exception: Exception) {
            exception.rethrowCancellation()
            metrics.countObservation(SourceObservationOutcome.FAILED)
            logger.logEvent(employmentCheckSourceObservationFailed, narmesteLederId, cause = exception)
        }
    }

    companion object {
        private val logger = applicationLogger(RecordSourceEmploymentRevocationUseCase::class.java)
    }
}
