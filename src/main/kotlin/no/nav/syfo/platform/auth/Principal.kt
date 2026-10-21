package no.nav.syfo.platform.auth

import io.ktor.server.auth.authentication
import io.ktor.server.routing.RoutingCall

sealed class Principal {
    abstract val ident: String
    abstract val token: String
}
data class UserPrincipal(
    override val ident: String,
    override val token: String,
) : Principal()

data class SystemPrincipal(
    override val ident: String,
    override val token: String,
    val systemOwner: String,
    val systemUserId: String,
) : Principal() {
    fun getSystemUserOrgNumber(): String = maskinportenIdToOrgnumber(ident)
}

fun RoutingCall.getMyPrincipal(): Principal = when (attributes[TOKEN_ISSUER]) {
    JwtIssuer.MASKINPORTEN -> {
        authentication.principal<SystemPrincipal>() ?: throw UnauthorizedException()
    }

    JwtIssuer.TOKEN_X -> {
        authentication.principal<UserPrincipal>() ?: throw UnauthorizedException()
    }

    else -> throw UnauthorizedException()
}

private fun maskinportenIdToOrgnumber(id: String): String = id.split(":")[1].trim()
