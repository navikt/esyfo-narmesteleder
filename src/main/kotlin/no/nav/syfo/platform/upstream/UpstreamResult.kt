package no.nav.syfo.platform.upstream

sealed interface UpstreamResult<out T> {
    data class Success<out T>(val value: T) : UpstreamResult<T>
    data class Failure(val failure: UpstreamFailure) : UpstreamResult<Nothing>
}

inline fun <T> UpstreamResult<T>.getOrElse(onFailure: (UpstreamFailure) -> T): T = when (this) {
    is UpstreamResult.Success -> value
    is UpstreamResult.Failure -> onFailure(failure)
}
