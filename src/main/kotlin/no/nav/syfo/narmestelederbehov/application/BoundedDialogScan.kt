package no.nav.syfo.narmestelederbehov.application

import kotlinx.coroutines.delay
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.applicationLogger
import no.nav.syfo.platform.observability.logEvent
import java.time.Instant
import kotlin.time.Duration

/** A behov that a bounded scan pages over in (created, id) order. */
interface DialogScanEntry {
    val id: NarmestelederbehovId
    val created: Instant
}

/** Returns the first `limit` behov created before `highWaterMark` and strictly after `after` in (created, id) order. */
typealias DialogScanFetch<T> = suspend (after: DialogScanPosition?, highWaterMark: Instant, limit: Int) -> List<T>

enum class DialogAttemptOutcome {
    SUCCEEDED,

    /** The behov stays eligible and is attempted again in the next cycle. */
    FAILED,
}

/** Counts for one run. [capped] is true when the run stopped at the batch limit and the cycle continues in the next run. */
data class DialogScanSummary(
    val flow: DialogScanFlow,
    val batches: Int,
    val attempted: Int,
    val failed: Int,
    val capped: Boolean,
)

/**
 * Attempts a flow's eligible behov in bounded runs. A cycle covers the behov created before its
 * high-water mark, oldest first, and can span several runs. Each run attempts at most [maxBatches]
 * batches of [batchSize] and continues after the saved position, so failures cannot prolong a run
 * and no behov is attempted twice in a cycle. Progress is saved after each full batch, so a crash
 * repeats at most one batch. A partial or empty batch ends the cycle, and the next run starts a new
 * cycle that retries the failed behov.
 */
class BoundedDialogScan(
    private val flow: DialogScanFlow,
    private val progressRepository: DialogScanProgressRepository,
    private val batchSize: Int,
    private val maxBatches: Int,
    private val batchDelay: Duration = Duration.ZERO,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxBatches > 0) { "maxBatches must be positive" }
    }

    /** Continues the flow's unfinished cycle, or starts a new one covering behov created before [newCycleHighWaterMark]. */
    suspend fun <T : DialogScanEntry> runOnce(
        newCycleHighWaterMark: Instant,
        fetch: DialogScanFetch<T>,
        attempt: suspend (T) -> DialogAttemptOutcome,
    ): DialogScanSummary {
        val summary = scanBatches(resumeOrStartCycle(newCycleHighWaterMark), fetch, attempt)
        logger.logEvent(dialogScanRunFinished, summary)
        return summary
    }

    private suspend fun resumeOrStartCycle(newCycleHighWaterMark: Instant): Cycle = progressRepository.find(flow)
        ?.let { Cycle(highWaterMark = it.highWaterMark, position = it.position) }
        ?: Cycle(highWaterMark = newCycleHighWaterMark, position = null)

    private suspend fun <T : DialogScanEntry> scanBatches(
        start: Cycle,
        fetch: DialogScanFetch<T>,
        attempt: suspend (T) -> DialogAttemptOutcome,
    ): DialogScanSummary {
        var cycle = start
        var summary = DialogScanSummary(flow, batches = 0, attempted = 0, failed = 0, capped = false)
        for (batchNumber in 1..maxBatches) {
            if (batchNumber > 1) delay(batchDelay)
            val batch = fetch(cycle.position, cycle.highWaterMark, batchSize)
            summary = summary.withBatch(batch.map { attempt(it) })
            if (batch.size < batchSize) {
                endCycle(cycle)
                return summary
            }
            cycle = saveProgress(cycle, batch.last())
        }
        return summary.copy(capped = true)
    }

    private suspend fun saveProgress(cycle: Cycle, last: DialogScanEntry): Cycle {
        val position = DialogScanPosition(created = last.created, id = last.id)
        progressRepository.save(flow, DialogScanProgress(position = position, highWaterMark = cycle.highWaterMark))
        return cycle.copy(position = position)
    }

    private suspend fun endCycle(cycle: Cycle) {
        // Progress is saved only after a full batch, so a cycle without a position has nothing to remove.
        if (cycle.position != null) progressRepository.reset(flow)
    }

    /** The cycle a run works on; [position] stays null until the cycle's first full batch. */
    private data class Cycle(val highWaterMark: Instant, val position: DialogScanPosition?)

    private companion object {
        val logger = applicationLogger(BoundedDialogScan::class.java)
    }
}

private fun DialogScanSummary.withBatch(outcomes: List<DialogAttemptOutcome>): DialogScanSummary = if (outcomes.isEmpty()) {
    this
} else {
    copy(
        batches = batches + 1,
        attempted = attempted + outcomes.size,
        failed = failed + outcomes.count { it == DialogAttemptOutcome.FAILED },
    )
}
