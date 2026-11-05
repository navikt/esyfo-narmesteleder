package no.nav.syfo.platform.auth

import io.ktor.server.auth.principal
import io.ktor.server.routing.RoutingCall
import no.nav.syfo.platform.api.ApiErrorException

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

fun RoutingCall.getMyPrincipal(): Principal = principal<Principal>() ?: throw ApiErrorException.UnauthorizedException()

private fun maskinportenIdToOrgnumber(id: String): String = id.split(":")[1].trim()
