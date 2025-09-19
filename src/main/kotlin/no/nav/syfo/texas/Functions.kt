package no.nav.syfo.texas

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.authorization
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.JwtIssuer.IDPORTEN
import no.nav.syfo.application.auth.JwtIssuer.MASKINPORTEN
import no.nav.syfo.application.auth.JwtIssuer.UNSUPPORTED
import java.util.Base64

fun ApplicationCall.bearerToken(): String? =
    request
        .authorization()
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.removePrefix("Bearer ")
        ?.removePrefix("bearer ")

/**
 *
 * @return A supported `JwtIssuer` from an unverified `JWT`. Returns `UNSUPPORTED` if none is applicable.
 * Throws `IllegalStateException` if unable to find a token or issuer.
 *
 * @see JwtIssuer
 * */
fun ApplicationCall.jwtIssuer(): JwtIssuer {
    val parts = bearerToken()
        ?.split(".")
        ?: error("No bearer token found")

    if (parts.size < 2) error("Invalid bearer token")

    val payloadJson = decodeJwtPart(parts[1])
    val jsonTree = jacksonObjectMapper()
        .readTree(payloadJson)

    val isTokenX = jsonTree.get("idp").asText().isNotBlank()
    if (isTokenX) return JwtIssuer.TOKEN_X // TODO: Kan også være entra(?). Sjekk om det er bedre måter å identifisere de ulike på

    val issuerString = jsonTree.get("iss")
        .asText()
        .takeIf { it.isNotEmpty() } ?: error("Invalid token")

    return JwtIssuer.fromIssuerString(issuerString)
}

private fun decodeJwtPart(encodedPart: String) = String(Base64.getUrlDecoder().decode(encodedPart))

private fun JwtIssuer.Companion.fromIssuerString(iss: String): JwtIssuer = when {
    // https://maskinporten.no/.well-known/oauth-authorization-server
    // https://test.maskinporten.no/.well-known/oauth-authorization-server
    iss.matches(Regex("https://(test\\.)?maskinporten\\.no/?")) -> MASKINPORTEN
    // https://idporten.no/.well-known/openid-configuration
    // https://test.idporten.no/.well-known/openid-configuration
    iss.matches(Regex("https://(test\\.)?idporten\\.no/?")) -> IDPORTEN
    else -> UNSUPPORTED
}

