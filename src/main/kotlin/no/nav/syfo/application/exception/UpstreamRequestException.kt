package no.nav.syfo.application.exception

enum class UpstreamExceptionType(
    val logValue: String,
) {
    CLIENT_REQUEST_EXCEPTION("ClientRequestException"),
    SERVER_RESPONSE_EXCEPTION("ServerResponseException"),
    REDIRECT_RESPONSE_EXCEPTION("RedirectResponseException"),
    RESPONSE_EXCEPTION("ResponseException"),
    UNEXPECTED_EXCEPTION("UnexpectedException"),
}

class UpstreamRequestException(
    message: String,
    cause: Throwable? = null,
    val upstreamStatus: Int? = null,
    val upstreamExceptionType: UpstreamExceptionType = UpstreamExceptionType.UNEXPECTED_EXCEPTION,
) : RuntimeException(message, cause)
