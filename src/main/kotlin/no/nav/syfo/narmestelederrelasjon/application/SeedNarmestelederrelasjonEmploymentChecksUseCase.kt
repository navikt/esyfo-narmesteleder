package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.logging.applicationLogger
import java.time.Clock

class SeedNarmestelederrelasjonEmploymentChecksUseCase(
    private val repository: EmploymentReconciliationRepository,
    private val settings: EmploymentCheckSettings,
    private val clock: Clock,
    private val metrics: EmploymentCheckMetrics,
) {
    suspend fun execute() {
        var rounds = 0
        var seededCount = 0L
        var seeded: Int
        do {
            seeded = repository.seedMissing(limit = settings.seedLimit, now = clock.instant())
            seededCount += seeded
            rounds++
        } while (seeded >= settings.seedLimit && rounds < MAX_SEED_ROUNDS)

        refreshComparisonGauges()
        logger.event(employmentCheckSeedCompleted, EmploymentCheckSeedSummary(seededCount, rounds))
    }

    private suspend fun refreshComparisonGauges() {
        metrics.refresh(repository.comparisonStats(now = clock.instant()))
    }

    companion object {
        const val MAX_SEED_ROUNDS = 50
        private val logger = applicationLogger(SeedNarmestelederrelasjonEmploymentChecksUseCase::class.java)
    }
}
