package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.getPageSize
import no.nav.syfo.narmesteleder.api.v1.toLinemanagerSearchCursor
import no.nav.syfo.narmesteleder.api.v1.toOpaqueCursor
import no.nav.syfo.narmesteleder.domain.LinemanagerReadCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchQuery
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchRequest
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.ILinemanagerSearchRepository

private const val TEXT_MAX_LENGTH = 50

class LinemanagerSearchService(
    private val validationService: ValidationService,
    private val linemanagerSearchRepository: ILinemanagerSearchRepository,
) {
    suspend fun search(
        request: LinemanagerSearchRequest,
        principal: Principal,
    ): LinemanagerReadCollection {
        validationService.validatePrincipalAccessToOrgnumber(principal, request.orgNumber)
        request.text?.let { text ->
            if (text.length > TEXT_MAX_LENGTH) {
                throw ApiErrorException.BadRequestException("text must be at most $TEXT_MAX_LENGTH characters")
            }
        }

        val pageSize = request.pageSize.getPageSize()
        val query = LinemanagerSearchQuery(
            orgNumber = request.orgNumber,
            managerNationalIdentificationNumber = request.managerNationalIdentificationNumber,
            employeeNationalIdentificationNumber = request.employeeNationalIdentificationNumber,
            nationalIdentificationNumber = request.text?.takeIf(String::isNationalIdentificationNumber)?.let(::PersonalIdentificationNumber),
            text = request.text?.takeUnless(String::isNationalIdentificationNumber),
            hasActiveSickLeave = request.hasActiveSickLeave,
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
}

private fun String.isNationalIdentificationNumber(): Boolean = length == 11 && all(Char::isDigit)
