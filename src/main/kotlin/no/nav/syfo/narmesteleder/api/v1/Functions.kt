package no.nav.syfo.narmesteleder.api.v1

import io.ktor.serialization.JsonConvertException
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.routing.RoutingCall
import java.util.UUID
import no.nav.syfo.application.exception.ApiErrorException

suspend inline fun <reified T : Any> RoutingCall.tryReceive() = runCatching { receive<T>() }.getOrElse {
    when {
        it is JsonConvertException -> throw BadRequestException("Invalid payload in request: ${it.message}", it)
        else -> throw it
    }
}

fun RoutingCall.getUUIDFromPathVariable(name: String): UUID {
    val idString = getPathVariable(name)
    return try {
        UUID.fromString(idString)
    } catch (_: IllegalArgumentException) {
        throw ApiErrorException.BadRequestException("Invalid UUID format for $name parameter")
    }
}

fun RoutingCall.getPathVariable(name: String): String {
    return this.parameters[name] ?: throw ApiErrorException.BadRequestException("Missing $name parameter")
}
fun RoutingCall.getQueryParameter(name: String): String {
    return this.queryParameters[name] ?: throw ApiErrorException.BadRequestException("Missing $name parameter")
}
