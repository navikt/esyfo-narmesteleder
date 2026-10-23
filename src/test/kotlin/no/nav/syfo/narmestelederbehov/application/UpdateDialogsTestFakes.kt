package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
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

internal fun updateFailure() = UpstreamFailure(
    UpstreamName("dialogporten"),
    UpstreamFailureStage.RESPONSE,
    503,
    IllegalStateException("private-diagnostic-canary"),
    errors = listOf(UpstreamError("server_error")),
)

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

internal class RecordingExpireDialogsRepository : ExpireNarmestelederbehovDialogsRepository {
    var expiryQueries = 0
    val expired = mutableListOf<Pair<NarmestelederbehovId, Instant>>()
    var findExpired: suspend () -> List<NarmestelederbehovDialogReference> = { emptyList() }
    var markExpired: suspend () -> Unit = {}

    override suspend fun findDialogsToExpire(): List<NarmestelederbehovDialogReference> {
        expiryQueries++
        return findExpired()
    }

    override suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant) {
        markExpired()
        expired += id to expirationTime
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

internal class ExpireDialogsFixture(batch: List<NarmestelederbehovDialogReference> = emptyList()) {
    val repository = RecordingExpireDialogsRepository().apply { findExpired = { batch } }
    val calls = mutableListOf<Pair<UUID, Instant>>()
    var expire: suspend (UUID) -> DialogExpiry = { DialogExpiry.Expired }
    val useCase = ExpireNarmestelederbehovDialogsUseCase(
        repository,
        { id, time ->
            calls += id to time
            expire(id)
        },
        UPDATE_CLOCK,
    )
}
