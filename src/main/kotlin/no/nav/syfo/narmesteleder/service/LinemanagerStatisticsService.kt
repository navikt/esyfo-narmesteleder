package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.narmesteleder.domain.LinemanagerStatistics
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.exposed.LinemanagerStatisticsRepository

class LinemanagerStatisticsService(
    private val validationService: ValidationService,
    private val linemanagerStatisticsRepository: LinemanagerStatisticsRepository,
) {
    suspend fun getStatistics(
        orgNumber: OrganizationNumber,
        principal: Principal,
    ): LinemanagerStatistics {
        validationService.validatePrincipalAccessToOrgnumber(principal, orgNumber)
        return linemanagerStatisticsRepository.getStatistics(orgNumber)
    }
}
