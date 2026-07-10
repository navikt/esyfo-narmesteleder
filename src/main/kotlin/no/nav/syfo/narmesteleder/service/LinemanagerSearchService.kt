package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.getPageSize
import no.nav.syfo.narmesteleder.api.v1.toLinemanagerSearchCursor
import no.nav.syfo.narmesteleder.api.v1.toOpaqueCursor
import no.nav.syfo.narmesteleder.domain.LinemanagerReadCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchQuery
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchRequest
import no.nav.syfo.narmesteleder.exposed.ILinemanagerSearchRepository

class LinemanagerSearchService(
    private val validationService: ValidationService,
    private val linemanagerSearchRepository: ILinemanagerSearchRepository,
) {
    suspend fun search(
        request: LinemanagerSearchRequest,
        principal: Principal,
    ): LinemanagerReadCollection {
        validationService.validatePrincipalAccessToOrgnumber(principal, request.orgNumber)
        validateRequest(request)

        val pageSize = request.pageSize.getPageSize()
        val query = LinemanagerSearchQuery(
            orgNumber = request.orgNumber,
            managerNationalIdentificationNumber = request.managerNationalIdentificationNumber,
            pageSize = pageSize,
            cursor = request.pageToken.toLinemanagerSearchCursor(),
        )
        val results = linemanagerSearchRepository.search(query)

        return LinemanagerReadCollection.from(
            results = results,
            pageSize = pageSize,
            toCursor = { it.toOpaqueCursor() },
        )
    }

    private fun validateRequest(request: LinemanagerSearchRequest) {
        if (request.text != null) {
            throw ApiErrorException.BadRequestException("Text search is not supported for this endpoint")
        }
    }
}
