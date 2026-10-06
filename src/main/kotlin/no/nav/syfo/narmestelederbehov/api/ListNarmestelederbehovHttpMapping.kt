package no.nav.syfo.narmestelederbehov.api

import io.ktor.server.routing.RoutingCall
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmesteleder.api.v1.getRequiredOrganizationNumberQueryParameter
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovQuery
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovResult
import no.nav.syfo.organisasjonstilgang.api.toOrganizationAccessSubject
import no.nav.syfo.platform.auth.getMyPrincipal

// Parse order is part of the contract: it decides which 400 a request with several invalid parameters gets.
fun RoutingCall.toListNarmestelederbehovQuery(): ListNarmestelederbehovQuery {
    val pageSize = getPageSize()
    val createdAfter = getCreatedAfter()
    val organizationNumber = getRequiredOrganizationNumberQueryParameter("orgNumber")
    return ListNarmestelederbehovQuery(
        organizationNumber = OrganizationNumber(organizationNumber.value),
        createdAfter = createdAfter,
        pageSize = pageSize,
        subject = getMyPrincipal().toOrganizationAccessSubject(),
    )
}

fun ListNarmestelederbehovResult.toLinemanagerRequirementCollection(pageSize: Int): LinemanagerRequirementCollection = when (this) {
    is ListNarmestelederbehovResult.Listed -> LinemanagerRequirementCollection(
        linemanagerRequirements = behov.map { it.behov.toLinemanagerRequirementRead(it.name, organizationName) },
        meta = PageInfo(size = behov.size, pageSize = pageSize, hasMore = hasMore, total = total),
    )
    is ListNarmestelederbehovResult.AccessDenied -> throw accessDeniedException(reason = reason, organizationNumber = organizationNumber)
    ListNarmestelederbehovResult.PersonNotFound ->
        throw ApiErrorException.InternalServerErrorException(errorMessage = "Internal server error")
}
