package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.CancellationException
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

interface ExpireNarmestelederbehovDialogsRepository {
    /**
     * BEHOV_EXPIRED or ERROR behov with a dialog and no expiry marker, created strictly before
     * [highWaterMark] and after [after] in (created, id) order, oldest first, up to [limit].
     */
    suspend fun findDialogsToExpire(after: DialogScanPosition?, highWaterMark: Instant, limit: Int): List<ExpirableNarmestelederbehovDialog>

    suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant)
}

class ExpireNarmestelederbehovDialogsUseCase(
    private val repository: ExpireNarmestelederbehovDialogsRepository,
    progressRepository: DialogScanProgressRepository,
    private val dialog: NarmestelederbehovDialogExpiry,
    private val clock: Clock,
) {
    private val scan = BoundedDialogScan(
        flow = DialogScanFlow.EXPIRE,
        progressRepository = progressRepository,
        batchSize = BATCH_SIZE,
        maxBatches = MAX_BATCHES,
        batchDelay = BATCH_DELAY,
    )

    suspend fun execute() {
        // A behov created after the cycle started cannot have expired before it, so it waits for
        // the next cycle instead of postponing this one.
        scan.runOnce(
            newCycleHighWaterMark = clock.instant(),
            fetch = repository::findDialogsToExpire,
            attempt = ::expireDialog,
        )
    }

    private suspend fun expireDialog(reference: ExpirableNarmestelederbehovDialog): DialogAttemptOutcome {
        val details = DialogEventDetails(reference.id.value, reference.dialogId).copy(action = DialogAction.EXPIRE)
        return try {
            // Dialogporten requires a future time, with slack for retries and processing.
            val expirationTime = clock.instant().plusSeconds(600)
            when (val result = dialog.expire(reference.dialogId, expirationTime)) {
                DialogExpiry.Expired -> {
                    repository.markDialogExpired(reference.id, expirationTime)
                    logger.info("Successfully updated expired behov ${reference.id.value} with expired date: $expirationTime")
                    DialogAttemptOutcome.SUCCEEDED
                }
                is DialogExpiry.Unavailable -> {
                    logger.logEvent(dialogportenDialogFailed, details, upstreamFailure = result.failure)
                    DialogAttemptOutcome.FAILED
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.logEvent(dialogportenDialogFailed, details, cause = e)
            DialogAttemptOutcome.FAILED
        }
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val MAX_BATCHES = 10
        val BATCH_DELAY = 100.milliseconds
        val logger = applicationLogger(ExpireNarmestelederbehovDialogsUseCase::class.java)
    }
}
