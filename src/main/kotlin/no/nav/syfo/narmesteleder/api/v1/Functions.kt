package no.nav.syfo.narmesteleder.api.v1

import io.ktor.serialization.JsonConvertException
import io.ktor.server.auth.authentication
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.routing.RoutingCall
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.JwtIssuer
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.TOKEN_ISSUER
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exceptions.UnauthorizedException
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchCursor
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID
import kotlin.text.Charsets.UTF_8

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

fun RoutingCall.getPathVariable(name: String): String = this.parameters[name] ?: throw ApiErrorException.BadRequestException("Missing $name parameter")

fun RoutingCall.getRequiredQueryParameter(name: String): String = this.queryParameters[name] ?: throw ApiErrorException.BadRequestException("Missing $name parameter")

fun RoutingCall.getRequiredOrganizationNumberQueryParameter(name: String): OrganizationNumber = OrganizationNumber.parse(getRequiredQueryParameter(name))
    .getOrElse {
        throw ApiErrorException.BadRequestException(
            it.message ?: "Invalid organization number format for $name parameter",
            type = ErrorType.INVALID_FORMAT
        )
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

fun Int?.getPageSize(): Int = when (this) {
    null -> LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE
    in 1..LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE -> this
    else -> LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE
}

fun String?.toLinemanagerSearchCursor(): LinemanagerSearchCursor? = this?.let { cursor ->
    runCatching {
        val decodedCursor = String(Base64.getUrlDecoder().decode(cursor), UTF_8)
        require(decodedCursor.startsWith(LINEMANAGER_SEARCH_CURSOR_PREFIX)) {
            "Unsupported cursor format"
        }
        val id = decodedCursor.removePrefix(LINEMANAGER_SEARCH_CURSOR_PREFIX).toInt()
        require(id > 0) {
            "Cursor id must be positive"
        }
        LinemanagerSearchCursor(id)
    }.getOrElse {
        throw ApiErrorException.BadRequestException(
            errorMessage = "Invalid pageToken",
            type = ErrorType.INVALID_FORMAT,
        )
    }
}

fun LinemanagerSearchCursor.toOpaqueCursor(): String = Base64.getUrlEncoder()
    .withoutPadding()
    .encodeToString("$LINEMANAGER_SEARCH_CURSOR_PREFIX$id".toByteArray(UTF_8))

fun RoutingCall.getMyPrincipal(): Principal = when (attributes[TOKEN_ISSUER]) {
    JwtIssuer.MASKINPORTEN -> {
        authentication.principal<SystemPrincipal>() ?: throw UnauthorizedException()
    }

    JwtIssuer.TOKEN_X -> {
        authentication.principal<UserPrincipal>() ?: throw UnauthorizedException()
    }

    else -> throw UnauthorizedException()
}

private const val LINEMANAGER_SEARCH_CURSOR_PREFIX = "v1:"
