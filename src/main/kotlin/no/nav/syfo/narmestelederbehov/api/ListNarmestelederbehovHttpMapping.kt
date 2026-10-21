package no.nav.syfo.narmestelederbehov.api

import io.ktor.server.routing.RoutingCall
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovQuery
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovResult
import no.nav.syfo.organisasjonstilgang.api.toForbiddenException
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.api.ApiErrorException
import no.nav.syfo.platform.api.ErrorType
import no.nav.syfo.platform.api.getRequiredQueryParameter
import no.nav.syfo.platform.api.toUpstreamUnavailableException
import no.nav.syfo.platform.auth.getMyPrincipal

// Parse order is part of the contract: it decides which 400 a request with several invalid parameters gets.
fun RoutingCall.toListNarmestelederbehovQuery(): ListNarmestelederbehovQuery {
    val pageSize = getPageSize()
    val createdAfter = getCreatedAfter()
    val organizationNumber = getRequiredOrganizationNumberQueryParameter("orgNumber")
    return ListNarmestelederbehovQuery(
        organizationNumber = organizationNumber,
        createdAfter = createdAfter,
        pageSize = pageSize,
        subject = getMyPrincipal().toOrganizationAccessSubject(),
    )
}

fun ListNarmestelederbehovResult.toLinemanagerRequirementCollection(pageSize: Int): LinemanagerRequirementCollection = when (this) {
    is ListNarmestelederbehovResult.UpstreamUnavailable -> throw failure.toUpstreamUnavailableException()
    is ListNarmestelederbehovResult.Listed -> LinemanagerRequirementCollection(
        linemanagerRequirements = behov.map { it.behov.toLinemanagerRequirementRead(it.name, organizationName) },
        meta = PageInfo(size = behov.size, pageSize = pageSize, hasMore = hasMore, total = total),
    )
    is ListNarmestelederbehovResult.AccessDenied -> throw reason.toForbiddenException(organizationNumber, isAlreadyLogged = true)
    ListNarmestelederbehovResult.PersonNotFound ->
        throw ApiErrorException.InternalServerErrorException(errorMessage = "Internal server error")
}

private fun RoutingCall.getRequiredOrganizationNumberQueryParameter(name: String): OrganizationNumber = getRequiredQueryParameter(name)
    .runCatching { OrganizationNumber(this) }
    .getOrElse {
        throw ApiErrorException.BadRequestException(
            it.message ?: "Invalid organization number format for $name parameter",
            type = ErrorType.INVALID_FORMAT
        )
    }
