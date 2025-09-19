package no.nav.syfo.application.auth

enum class JwtIssuer {
    IDPORTEN,
    MASKINPORTEN,
    TOKEN_X,
    ENTRA,
    UNSUPPORTED;

    companion object
}