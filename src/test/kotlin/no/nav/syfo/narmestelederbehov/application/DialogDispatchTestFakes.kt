package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

internal class FakeQueue(
    private val claimBlock: suspend () -> DialogClaim? = { CLAIM },
    private val finishBlock: suspend () -> Boolean = { true },
) : DialogDispatchQueue {
    var acknowledged = false
    var finishedResult: DialogDispatchResult? = null
    var claims = 0

    override suspend fun claim(now: Instant, lease: Duration): DialogClaim? {
        claims++
        return claimBlock()
    }

    override suspend fun finish(claim: DialogClaim, result: DialogDispatchResult, now: Instant): Boolean {
        finishedResult = result
        return finishBlock().also { acknowledged = it }
    }

    companion object {
        private val CLAIM = DialogClaim(
            id = NarmestelederbehovId(UUID.randomUUID()),
            token = UUID.randomUUID(),
            revision = 1,
            leaseUntil = Instant.parse("2030-01-01T10:01:00Z"),
            attempts = 0,
        )
    }
}

internal class FakeWork(
    private val state: DialogDispatchState?,
    private val loadBlock: suspend () -> DialogDispatchState? = { state },
) : DialogDispatchWork {
    override suspend fun load(claim: DialogClaim, now: Instant): DialogDispatchState? = loadBlock()
    override suspend fun beginCreation(claim: DialogClaim, now: Instant) = true
    override suspend fun saveCreated(claim: DialogClaim, dialogId: UUID, name: BehovPersonName?, now: Instant) = true
    override suspend fun prepareExpiry(claim: DialogClaim, expiry: Instant, now: Instant) = true
    override suspend fun saveCompleted(claim: DialogClaim, now: Instant) = true
    override suspend fun saveExpired(claim: DialogClaim, expiry: Instant, now: Instant) = true
}
