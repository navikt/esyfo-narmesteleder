package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant

/**
 * A Dialogporten background flow that keeps its own scan progress. The names are persisted, so
 * adding or renaming a flow needs a database migration.
 */
enum class DialogScanFlow {
    SEND,
    EXPIRE,
}

/** The last attempted behov in (created, id) order, so equal created values are neither skipped nor repeated. */
data class DialogScanPosition(val created: Instant, val id: NarmestelederbehovId)

/** An unfinished cycle: the scan continues after [position] and ignores behov created at or after [highWaterMark]. */
data class DialogScanProgress(val position: DialogScanPosition, val highWaterMark: Instant)

interface DialogScanProgressRepository {
    /** Null when [flow] has no unfinished cycle. */
    suspend fun find(flow: DialogScanFlow): DialogScanProgress?

    /** Creates or replaces the progress for [flow]. */
    suspend fun save(flow: DialogScanFlow, progress: DialogScanProgress)

    /** Removes the progress for [flow], so its next run starts a new cycle. */
    suspend fun reset(flow: DialogScanFlow)
}
