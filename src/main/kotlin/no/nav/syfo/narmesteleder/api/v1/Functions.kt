package no.nav.syfo.narmesteleder.api.v1

import io.ktor.serialization.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.exception.ApiErrorException

suspend inline fun <reified T : Any> RoutingCall.tryReceive() = runCatching { receive<T>() }.getOrElse {
    when {
        it is JsonConvertException -> throw BadRequestException("Invalid payload in request: ${it.message}", it)
        else -> throw it
    }
}

fun RoutingCall.consumerIdFromPrincipal() = principal< OrganisasjonPrincipal>()?.ident
    ?: throw ApiErrorException.UnauthorizedException("No principal found for user")
