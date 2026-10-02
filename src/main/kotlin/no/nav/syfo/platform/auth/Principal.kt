package no.nav.syfo.platform.auth

import io.ktor.server.auth.authentication
import io.ktor.server.routing.RoutingCall
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exceptions.UnauthorizedException

fun RoutingCall.getMyPrincipal(): Principal = when (attributes[TOKEN_ISSUER]) {
    JwtIssuer.MASKINPORTEN -> {
        authentication.principal<SystemPrincipal>() ?: throw UnauthorizedException()
    }

    JwtIssuer.TOKEN_X -> {
        authentication.principal<UserPrincipal>() ?: throw UnauthorizedException()
    }

    else -> throw UnauthorizedException()
}
