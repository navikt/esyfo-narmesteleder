package no.nav.syfo.platform.upstream

import no.nav.syfo.application.exception.UpstreamRequestException

sealed interface UpstreamResult<out T> {
    data class Success<out T>(val value: T) : UpstreamResult<T>
    data class Failure(val failure: UpstreamFailure) : UpstreamResult<Nothing>
}

/** Legacy bridge only; migrated callers propagate failures as values (ADR-0004). */
fun <T> UpstreamResult<T>.getOrThrow(): T = when (this) {
    is UpstreamResult.Success -> value
    is UpstreamResult.Failure -> throw UpstreamRequestException(
        message = "${failure.upstream.value} failed at ${failure.stage.logValue}",
        cause = failure.cause,
        upstreamStatus = failure.status,
        failureStage = failure.stage,
        upstream = failure.upstream.value,
    )
}

inline fun <T> UpstreamResult<T>.getOrElse(onFailure: (UpstreamFailure) -> T): T = when (this) {
    is UpstreamResult.Success -> value
    is UpstreamResult.Failure -> onFailure(failure)
}
