package no.nav.syfo.platform.api

import io.ktor.server.routing.RoutingCall
import java.util.UUID

fun RoutingCall.getUUIDFromPathVariable(name: String): UUID {
    val idString = getPathVariable(name)
    return try {
        UUID.fromString(idString)
    } catch (_: IllegalArgumentException) {
        throw ApiErrorException.BadRequestException("Invalid UUID format for $name parameter")
    }
}

fun RoutingCall.getPathVariable(name: String): String = this.parameters[name] ?: throw ApiErrorException.BadRequestException("Missing $name parameter")

fun RoutingCall.getRequiredQueryParameter(name: String): String = this.queryParameters[name] ?: throw ApiErrorException.BadRequestException("Missing $name parameter")
