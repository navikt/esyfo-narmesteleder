package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import java.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class ExpireNarmestelederbehovDialogsUseCase(
    private val repository: NarmestelederbehovRepository,
    private val dialog: NarmestelederbehovDialogExpiry,
    private val clock: Clock,
) {
    suspend fun execute() {
        var batchNum = 0
        do {
            val batch = repository.findDialogsToExpire()
            logger.info("Batch: ${++batchNum}:: Found ${batch.size} expired behovs to expire in dialogporten")
            for ((id, dialogId) in batch) {
                val details = DialogEventDetails(id.value, dialogId).copy(action = DialogAction.EXPIRE)
                try {
                    // Dialogporten requires a future time, with slack for retries and processing.
                    val expirationTime = clock.instant().plusSeconds(600)
                    when (val result = dialog.expire(dialogId, expirationTime)) {
                        DialogExpiry.Expired -> {
                            repository.markDialogExpired(id, expirationTime)
                            logger.info("Successfully updated expired behov ${id.value} with expired date: $expirationTime")
                        }
                        is DialogExpiry.Unavailable -> logger.logEvent(dialogportenDialogFailed, details, upstreamFailure = result.failure)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.logEvent(dialogportenDialogFailed, details, cause = e)
                }
            }
            delay(100.milliseconds)
        } while (batch.size >= BATCH_SIZE && batchNum < MAX_BATCHES)
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val MAX_BATCHES = 1000
        val logger = applicationLogger(ExpireNarmestelederbehovDialogsUseCase::class.java)
    }
}
