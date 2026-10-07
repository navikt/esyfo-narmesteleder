package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.delay
import no.nav.esyfo.observability.Event
import no.nav.syfo.logging.applicationLogger
import org.slf4j.event.Level
import java.time.Clock
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ExpireNarmestelederbehovUseCase(
    private val repository: NarmestelederbehovExpiryRepository,
    private val settings: NarmestelederbehovExpirySettings,
    private val clock: Clock,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val pauseBetweenBatches: Duration = DEFAULT_PAUSE_BETWEEN_BATCHES,
) {
    suspend fun execute() {
        val tomBefore = LocalDate.now(clock).minusDays(settings.daysAfterTom)
        var expiredInBatch: Int
        var totalExpired = 0

        do {
            expiredInBatch = repository.expireOpenWithSykmeldingTomBefore(tomBefore, batchSize)
            totalExpired += expiredInBatch
            delay(pauseBetweenBatches)
        } while (expiredInBatch > 0)

        logger.event(expiryCompleted, ExpiryCompleted(totalExpired, tomBefore))
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 500
        val DEFAULT_PAUSE_BETWEEN_BATCHES = 500.milliseconds
        private val logger = applicationLogger(ExpireNarmestelederbehovUseCase::class.java)
    }
}

private data class ExpiryCompleted(val expiredCount: Int, val tomBefore: LocalDate)

private val expiryCompleted = Event<ExpiryCompleted>(
    name = "narmestelederbehov_expiry_completed",
    level = Level.INFO,
    message = "Narmestelederbehov expiry completed",
    operation = "expire_narmestelederbehov",
    fields = mapOf(
        "expired_count" to { it.expiredCount },
        "tom_before" to { it.tomBefore.toString() },
    ),
)
