package no.nav.syfo.narmesteleder.api.v1

import io.ktor.serialization.JsonConvertException
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.routing.RoutingCall
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.exception.ApiErrorException

suspend inline fun <reified T : Any> RoutingCall.tryReceive() = runCatching { receive<T>() }.getOrElse {
    when {
        it is JsonConvertException -> throw BadRequestException("Invalid payload in request: ${it.message}", it)
        else -> throw it
    }
}
