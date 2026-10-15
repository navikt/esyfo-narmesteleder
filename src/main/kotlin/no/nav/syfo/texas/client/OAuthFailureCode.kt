package no.nav.syfo.texas.client

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
