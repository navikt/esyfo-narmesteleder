package no.nav.syfo.narmestelederrelasjon.application.employmentcheck

import no.nav.syfo.platform.observability.applicationLogger
import java.time.Clock

/** Adds new active relations to the check queue and refreshes the backlog gauge. */
class SeedNarmestelederrelasjonEmploymentChecksUseCase(
    private val repository: EmploymentReconciliationRepository,
    private val settings: EmploymentCheckSettings,
    private val clock: Clock,
    private val metrics: EmploymentCheckMetrics,
) {
    suspend fun execute() {
        val seeded = repository.seedMissing(limit = settings.seedLimit, now = clock.instant())
        metrics.refresh(repository.stats(now = clock.instant()))
        logger.event(employmentCheckSeedCompleted, seeded)
    }

    companion object {
        private val logger = applicationLogger(SeedNarmestelederrelasjonEmploymentChecksUseCase::class.java)
    }
}
