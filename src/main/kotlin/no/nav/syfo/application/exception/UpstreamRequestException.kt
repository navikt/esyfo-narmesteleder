package no.nav.syfo.application.exception

enum class UpstreamExceptionType(
    val logValue: String,
) {
    CLIENT_REQUEST_EXCEPTION("ClientRequestException"),
    SERVER_RESPONSE_EXCEPTION("ServerResponseException"),
    REDIRECT_RESPONSE_EXCEPTION("RedirectResponseException"),
    RESPONSE_EXCEPTION("ResponseException"),
    TRANSPORT_EXCEPTION("TransportException"),
    RESPONSE_DECODING_EXCEPTION("ResponseDecodingException"),
    UNEXPECTED_EXCEPTION("UnexpectedException"),
}

enum class UpstreamFailureStage(
    val logValue: String,
) {
    TOKEN_EXCHANGE("token_exchange"),
    REQUEST("request"),
    RESPONSE("response"),
}

class UpstreamRequestException(
    message: String,
    cause: Throwable? = null,
    upstreamStatus: Int? = null,
    val upstreamExceptionType: UpstreamExceptionType = UpstreamExceptionType.UNEXPECTED_EXCEPTION,
    val failureStage: UpstreamFailureStage = UpstreamFailureStage.REQUEST,
) : RuntimeException(message, cause) {
    val upstreamStatus: Int? = upstreamStatus?.takeIf { it in 100..599 }
}
