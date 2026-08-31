package no.nav.syfo.application.exception

class UpstreamRequestException(
    message: String,
    cause: Throwable? = null,
    val upstreamStatus: Int? = null,
    val upstreamExceptionType: String? = cause?.javaClass?.simpleName,
) : RuntimeException(message, cause)
