package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

data class DialogClaim(val id: NarmestelederbehovId, val token: UUID, val revision: Long, val leaseUntil: Instant, val attempts: Int)

data class DialogDispatchState(
    val employee: Employee,
    val status: BehovStatus,
    val dialogId: UUID?,
    val createStarted: Boolean,
    val intendedExpiry: Instant?,
    val expiredAt: Instant?,
)

sealed interface DialogDispatchResult {
    data object Done : DialogDispatchResult
    data class Retry(val nextAttempt: Instant, val reason: DialogDispatchFailure) : DialogDispatchResult
    data class Parked(val reason: DialogDispatchFailure) : DialogDispatchResult
}

enum class DialogDispatchFailure(val value: String) {
    INVALID_REQUEST("invalid_request"),
    UPSTREAM("upstream"),
    TIMEOUT("timeout"),
    UNEXPECTED("unexpected"),
}

interface DialogDispatchQueue {
    suspend fun claim(now: Instant, lease: Duration): DialogClaim?
    suspend fun finish(claim: DialogClaim, result: DialogDispatchResult, now: Instant): Boolean
}

interface DialogDispatchWork {
    suspend fun load(claim: DialogClaim, now: Instant): DialogDispatchState?
    suspend fun beginCreation(claim: DialogClaim, now: Instant): Boolean
    suspend fun saveCreated(claim: DialogClaim, dialogId: UUID, name: BehovPersonName?, now: Instant): Boolean
    suspend fun prepareExpiry(claim: DialogClaim, expiry: Instant, now: Instant): Boolean
    suspend fun saveCompleted(claim: DialogClaim, now: Instant): Boolean
    suspend fun saveExpired(claim: DialogClaim, expiry: Instant, now: Instant): Boolean
}

fun interface DialogDispatchStatsReader {
    suspend fun stats(): DialogDispatchStats
}

data class DialogDispatchStats(val pending: Long, val parked: Long, val oldestPending: Instant?)
