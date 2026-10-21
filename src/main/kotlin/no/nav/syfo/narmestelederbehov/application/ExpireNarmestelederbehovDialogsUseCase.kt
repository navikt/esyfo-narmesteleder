package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
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
            for (behov in batch) {
                val details = DialogEventDetails(behov.id.value, behov.dialogId).copy(action = DialogAction.EXPIRE)
                try {
                    // Dialogporten requires a future time, with slack for retries and processing.
                    val expirationTime = clock.instant().plusSeconds(600)
                    when (val result = dialog.expire(behov.dialogId, expirationTime)) {
                        DialogExpiry.Expired -> {
                            repository.markDialogExpired(behov.id, expirationTime)
                            logger.info("Successfully updated expired behov ${behov.id.value} with expired date: $expirationTime")
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
