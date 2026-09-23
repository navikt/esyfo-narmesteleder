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
    val upstreamErrorCode: OAuthFailureCode? = null,
    val upstream: String? = null,
) : RuntimeException(message, cause) {
    val upstreamStatus: Int? = upstreamStatus?.takeIf { it in 100..599 }
}

/** OAuth error values allowed in operational logs. Unknown provider values stay unknown. */
enum class OAuthFailureCode {
    INVALID_REQUEST,
    INVALID_CLIENT,
    INVALID_GRANT,
    UNAUTHORIZED_CLIENT,
    UNSUPPORTED_GRANT_TYPE,
    INVALID_SCOPE,
    ACCESS_DENIED,
    SERVER_ERROR,
    TEMPORARILY_UNAVAILABLE,
    INVALID_TARGET,
    UNKNOWN,
}
