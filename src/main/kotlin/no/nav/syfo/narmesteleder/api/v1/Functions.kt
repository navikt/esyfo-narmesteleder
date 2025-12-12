package no.nav.syfo.narmesteleder.api.v1

import io.ktor.serialization.JsonConvertException
import io.ktor.server.auth.authentication
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.routing.RoutingCall
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exceptions.UnauthorizedException
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementCollection

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

fun RoutingCall.getRequiredQueryParameter(name: String): String {
    return this.queryParameters[name] ?: throw ApiErrorException.BadRequestException("Missing $name parameter")
}

fun RoutingCall.getCreatedAfter(): Instant {
    val createdAfter = getRequiredQueryParameter("createdAfter")
    try {
        return Instant.parse(createdAfter)
    } catch (e: DateTimeParseException) {
        throw ApiErrorException.BadRequestException(
            "Invalid date format for createdAfter parameter. Expected ISO-8601 format.",
            type = ErrorType.BAD_REQUEST
        )
    }
}

fun RoutingCall.getPageSize(): Int {
    val pageSize = this.queryParameters["pageSize"]
        ?.toIntOrNull()
    return when (pageSize) {
        null -> LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE
        in 1..LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE -> pageSize
        else -> LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE
    }
}

fun RoutingCall.getMyPrincipal(): Principal =
    when (attributes[TOKEN_ISSUER]) {
        JwtIssuer.MASKINPORTEN -> {
            authentication.principal<SystemPrincipal>() ?: throw UnauthorizedException()
        }

        JwtIssuer.TOKEN_X -> {
            authentication.principal<UserPrincipal>() ?: throw UnauthorizedException()
        }

        else -> throw UnauthorizedException()
    }
