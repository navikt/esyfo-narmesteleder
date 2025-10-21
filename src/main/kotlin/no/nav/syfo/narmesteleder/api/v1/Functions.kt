package no.nav.syfo.narmesteleder.api.v1

import io.ktor.serialization.JsonConvertException
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.routing.RoutingCall
import java.util.UUID
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.exception.ApiErrorException

suspend inline fun <reified T : Any> RoutingCall.tryReceive() = runCatching { receive<T>() }.getOrElse {
    when {
        it is JsonConvertException -> throw BadRequestException("Invalid payload in request: ${it.message}", it)
        else -> throw it
    }
}

fun RoutingCall.getUUIDFromPathVariable(name: String): UUID {
    val idString = this.parameters[name] ?: throw IllegalArgumentException("Missing $name parameter")
    return try {
        UUID.fromString(idString)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid UUID format for $name parameter")
    }
}
