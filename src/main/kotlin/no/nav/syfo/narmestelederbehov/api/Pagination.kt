package no.nav.syfo.narmestelederbehov.api

import io.ktor.server.routing.RoutingCall
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.getRequiredQueryParameter
import java.time.Instant
import java.time.format.DateTimeParseException

fun RoutingCall.getCreatedAfter(): Instant {
    val createdAfter = getRequiredQueryParameter("createdAfter")
    try {
        return Instant.parse(createdAfter)
    } catch (_: DateTimeParseException) {
        throw ApiErrorException.BadRequestException(
            "Invalid date format for createdAfter parameter. Expected ISO-8601 format.",
            type = ErrorType.BAD_REQUEST,
        )
    }
}

fun RoutingCall.getPageSize(): Int {
    val pageSize = queryParameters["pageSize"]?.toIntOrNull()
    return when (pageSize) {
        null -> LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE
        in 1..LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE -> pageSize
        else -> LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE
    }
}
