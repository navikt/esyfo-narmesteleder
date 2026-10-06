package no.nav.syfo.narmesteleder.api.v1

import io.ktor.server.routing.RoutingCall
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementCollection
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import java.time.Instant
import java.time.format.DateTimeParseException
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
