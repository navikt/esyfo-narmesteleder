package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

interface ExpireNarmestelederbehovDialogsRepository {
    /** Up to 100 BEHOV_EXPIRED or ERROR behov with dialogs and no expiry marker, oldest first. */
    suspend fun findDialogsToExpire(): List<NarmestelederbehovDialogReference>

    suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant)
}

class ExpireNarmestelederbehovDialogsUseCase(
    private val repository: ExpireNarmestelederbehovDialogsRepository,
    private val dialog: NarmestelederbehovDialogExpiry,
    private val clock: Clock,
) {
    suspend fun execute() {
        var batchNum = 0
        do {
            val batch = repository.findDialogsToExpire()
            logger.info("Batch: ${++batchNum}:: Found ${batch.size} expired behovs to expire in dialogporten")
            expireDialogs(batch)
            delay(100.milliseconds)
        } while (batch.size >= BATCH_SIZE && batchNum < MAX_BATCHES)
    }

    private suspend fun expireDialogs(batch: List<NarmestelederbehovDialogReference>) {
        batch.forEach { reference -> expireDialog(reference) }
    }

    private suspend fun expireDialog(reference: NarmestelederbehovDialogReference) {
        val details = DialogEventDetails(reference.id.value, reference.dialogId).copy(action = DialogAction.EXPIRE)
        try {
            // Dialogporten requires a future time, with slack for retries and processing.
            val expirationTime = clock.instant().plusSeconds(600)
            when (val result = dialog.expire(reference.dialogId, expirationTime)) {
                DialogExpiry.Expired -> {
                    repository.markDialogExpired(reference.id, expirationTime)
                    logger.info("Successfully updated expired behov ${reference.id.value} with expired date: $expirationTime")
                }
                is DialogExpiry.Unavailable -> logger.logEvent(dialogportenDialogFailed, details, upstreamFailure = result.failure)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenDialogFailed, details, cause = e)
        }
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val MAX_BATCHES = 1000
        val logger = applicationLogger(ExpireNarmestelederbehovDialogsUseCase::class.java)
    }
}
