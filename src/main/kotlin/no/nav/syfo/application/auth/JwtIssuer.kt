package no.nav.syfo.application.auth

enum class JwtIssuer(val value: String? = null) {
    IDPORTEN("idporten"),
    MASKINPORTEN("maskinporten"),
    TOKEN_X("tokenx"),
    FAKEDINGS("fakedings"),
    UNSUPPORTED;

    companion object
}