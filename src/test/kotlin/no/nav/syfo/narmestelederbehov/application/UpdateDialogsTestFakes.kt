package no.nav.syfo.narmestelederbehov.application

import io.kotest.matchers.shouldBe
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal val UPDATE_NOW: Instant = Instant.parse("2026-10-01T10:00:00Z")
internal val UPDATE_CLOCK: Clock = Clock.fixed(UPDATE_NOW, ZoneOffset.UTC)

internal fun dialogReference(number: Int = 1) = NarmestelederbehovDialogReference(NarmestelederbehovId(UUID(0, number.toLong())), UUID(1, number.toLong()))

internal fun expirableDialog(number: Int = 1) = ExpirableNarmestelederbehovDialog(
    id = NarmestelederbehovId(UUID(0, number.toLong())),
    dialogId = UUID(1, number.toLong()),
    created = UPDATE_NOW.minusSeconds(600),
)

internal fun updateFailure() = UpstreamFailure(
    UpstreamName("dialogporten"),
    UpstreamFailureStage.RESPONSE,
    503,
    IllegalStateException("private-diagnostic-canary"),
    errors = listOf(UpstreamError("server_error")),
)

private fun compareUuid(left: UUID, right: UUID): Int {
    val mostSignificantBits = java.lang.Long.compareUnsigned(left.mostSignificantBits, right.mostSignificantBits)
    return if (mostSignificantBits != 0) {
        mostSignificantBits
    } else {
        java.lang.Long.compareUnsigned(left.leastSignificantBits, right.leastSignificantBits)
    }
}

/** Pages [entries] with the same keyset rule as the repositories: oldest first, strictly after [after]. */
internal fun <T : DialogScanEntry> scanPage(entries: List<T>, after: DialogScanPosition?, highWaterMark: Instant, limit: Int): List<T> = entries
    .filter { it.created < highWaterMark }
    .filter {
        after == null ||
            it.created > after.created ||
            (it.created == after.created && compareUuid(it.id.value, after.id.value) > 0)
    }
    .sortedWith { left, right ->
        val createdComparison = left.created.compareTo(right.created)
        if (createdComparison != 0) createdComparison else compareUuid(left.id.value, right.id.value)
    }
    .take(limit)

/** The position a scan saves after attempting this behov. */
internal val DialogScanEntry.position: DialogScanPosition get() = DialogScanPosition(created, id)

/** The [BoundedDialogScan] run events in this capture, in run order. */
internal fun ProductionLogCapture.scanSummaries(): List<DialogScanSummary> = logs.map { record ->
    record.eventType shouldBe "dialog_scan_run_finished"
    DialogScanSummary(
        flow = DialogScanFlow.valueOf(record["flow"] as String),
        batches = record["batches"] as Int,
        attempted = record["attempted"] as Int,
        failed = record["failed"] as Int,
        capped = record["capped"] as Boolean,
    )
}

/** In-memory stand-in for the durable progress table, keyed per flow. */
internal class RecordingDialogScanProgressRepository : DialogScanProgressRepository {
    val state = mutableMapOf<DialogScanFlow, DialogScanProgress>()
    var writes = 0
        private set

    override suspend fun find(flow: DialogScanFlow): DialogScanProgress? = state[flow]

    override suspend fun save(flow: DialogScanFlow, progress: DialogScanProgress) {
        writes++
        state[flow] = progress
    }

    override suspend fun reset(flow: DialogScanFlow) {
        writes++
        state.remove(flow)
    }
}

internal class RecordingCompleteDialogsRepository : CompleteFulfilledNarmestelederbehovDialogsRepository {
    val fulfilledQueries = mutableListOf<Instant>()
    var findFulfilled: suspend () -> List<NarmestelederbehovDialogReference> = { emptyList() }
    val marked = mutableListOf<NarmestelederbehovId>()
    var mark: suspend () -> MarkDialogCompletedResult = { MarkDialogCompletedResult.Marked }

    override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult {
        val result = mark()
        marked += id
        return result
    }

    override suspend fun findFulfilledDialogs(createdBefore: Instant): List<NarmestelederbehovDialogReference> {
        fulfilledQueries += createdBefore
        return findFulfilled()
    }
}

/**
 * In-memory stand-in for the expiry repository. Marking a behov expired takes it out of the
 * eligible set, like setting expiredInDialogporten does in PostgreSQL.
 */
internal class RecordingExpireDialogsRepository(entries: List<ExpirableNarmestelederbehovDialog> = emptyList()) : ExpireNarmestelederbehovDialogsRepository {
    private val eligible = entries.toMutableList()
    var expiryQueries = 0
    val expired = mutableListOf<Pair<NarmestelederbehovId, Instant>>()
    var findExpired: DialogScanFetch<ExpirableNarmestelederbehovDialog> =
        { after, highWaterMark, limit -> scanPage(eligible, after, highWaterMark, limit) }
    var markExpired: suspend () -> Unit = {}

    override suspend fun findDialogsToExpire(after: DialogScanPosition?, highWaterMark: Instant, limit: Int): List<ExpirableNarmestelederbehovDialog> {
        expiryQueries++
        return findExpired(after, highWaterMark, limit)
    }

    override suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant) {
        markExpired()
        expired += id to expirationTime
        eligible.removeAll { it.id == id }
    }
}

internal class CompleteDialogsFixture(batch: List<NarmestelederbehovDialogReference> = emptyList()) {
    val repository = RecordingCompleteDialogsRepository().apply { findFulfilled = { batch } }
    val calls = mutableListOf<UUID>()
    var complete: suspend (UUID) -> DialogCompletion = { DialogCompletion.Completed }
    val useCase = CompleteFulfilledNarmestelederbehovDialogsUseCase(
        repository,
        { id ->
            calls += id
            complete(id)
        },
        UPDATE_CLOCK,
    )
}

internal class ExpireDialogsFixture(batch: List<ExpirableNarmestelederbehovDialog> = emptyList()) {
    val repository = RecordingExpireDialogsRepository(batch)
    val progress = RecordingDialogScanProgressRepository()
    val calls = mutableListOf<Pair<UUID, Instant>>()
    var expire: suspend (UUID) -> DialogExpiry = { DialogExpiry.Expired }
    val useCase = ExpireNarmestelederbehovDialogsUseCase(
        repository,
        progress,
        { id, time ->
            calls += id to time
            expire(id)
        },
        UPDATE_CLOCK,
    )
}
