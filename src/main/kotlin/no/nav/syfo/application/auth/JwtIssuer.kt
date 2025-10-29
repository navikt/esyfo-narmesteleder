package no.nav.syfo.application.auth

enum class JwtIssuer(val value: String? = null) {
    IDPORTEN("idporten"),
    MASKINPORTEN("maskinporten"),
    TOKEN_X("tokenx"),
    FAKEDINGS("fakedings"),
    UNSUPPORTED;


    companion object {
        fun fromIssuerString(iss: String): JwtIssuer = when {
            // https://maskinporten.no/.well-known/oauth-authorization-server
            // https://test.maskinporten.no/.well-known/oauth-authorization-server
            iss.matches(Regex("https://(test\\.)?maskinporten\\.no/?")) -> MASKINPORTEN
            // https://idporten.no/.well-known/openid-configuration
            // https://test.idporten.no/.well-known/openid-configuration
//            iss.matches(Regex("https://(test\\.)?idporten\\.no/?")) -> IDPORTEN
            iss.contains("tokenx") -> TOKEN_X
            // tokenx is found at well-known doc found in TOKEN_X_WELL_KNOWN_URL env. var
            else -> JwtIssuer.UNSUPPORTED
        }
    }
}
