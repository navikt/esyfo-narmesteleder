package no.nav.syfo.narmesteleder.service

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.narmesteleder.domain.LinemanagerStatistics
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.exposed.ILinemanagerStatisticsRepository

class LinemanagerStatisticsService(
    private val validationService: ValidationService,
    private val linemanagerStatisticsRepository: ILinemanagerStatisticsRepository,
) {
    suspend fun getStatistics(
        orgNumber: OrganizationNumber,
        principal: Principal,
    ): LinemanagerStatistics {
        validationService.validatePrincipalAccessToOrgnumber(principal, orgNumber)
        return linemanagerStatisticsRepository.getStatistics(orgNumber)
    }
}
