package no.nav.syfo.narmestelederbehov.api

import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovResult

fun ListNarmestelederbehovResult.toLinemanagerRequirementCollection(pageSize: Int): LinemanagerRequirementCollection = when (this) {
    is ListNarmestelederbehovResult.Listed -> LinemanagerRequirementCollection(
        linemanagerRequirements = behov.map { it.behov.toLinemanagerRequirementRead(it.name, organizationName) },
        meta = PageInfo(size = behov.size, pageSize = pageSize, hasMore = hasMore, total = total),
    )
    is ListNarmestelederbehovResult.AccessDenied -> throw accessDeniedException(reason = reason, organizationNumber = organizationNumber)
    ListNarmestelederbehovResult.PersonNotFound ->
        throw ApiErrorException.InternalServerErrorException(errorMessage = "Internal server error", isAlreadyLogged = true)
}
