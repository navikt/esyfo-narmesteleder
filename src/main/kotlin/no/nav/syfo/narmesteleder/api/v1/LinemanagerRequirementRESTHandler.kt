package no.nav.syfo.narmesteleder.api.v1

import kotlinx.coroutines.CancellationException
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.util.logger
import java.time.Instant
import java.util.UUID

class LinemanagerRequirementRESTHandler(
    private val narmesteLederService: NarmestelederService,
    private val validationService: ValidationService,
) {
    companion object {
        val logger = logger()
    }

    suspend fun handleGetLinemanagerRequirement(requirementId: UUID, principal: Principal): LinemanagerRequirementRead = try {
        narmesteLederService.getLinemanagerRequirementReadById(requirementId).let {
            val orgNavn = validationService.validatePrincipalAccessToOrgnumber(principal, it.orgNumber)
            it.copy(orgName = orgNavn)
        }
    } catch (e: LinemanagerRequirementNotFoundException) {
        throw ApiErrorException.NotFoundException("LinemanagerRequirement", e)
    } catch (e: ApiErrorException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ApiErrorException.InternalServerErrorException(
            "Something went wrong while fetching LinemanagerRequirement",
            e
        )
    }

    suspend fun handleGetLinemanagerRequirementsCollection(
        pageSize: Int,
        createdAfter: Instant,
        orgNumber: OrganizationNumber,
        principal: Principal
    ): LinemanagerRequirementCollection {
        val orgName = validationService.validatePrincipalAccessToOrgnumber(principal, orgNumber)
        logger.info("Validation successful for fetching LinemanagerRequirement collection for orgNumber: ${orgNumber.value}")
        val requirements = narmesteLederService.getNlBehovList(
            pageSize = pageSize,
            createdAfter = createdAfter,
            orgNumber = orgNumber
        ).map { it.copy(orgName = orgName) }
        val total = if (requirements.size > pageSize) {
            narmesteLederService.countNlBehov(
                orgNumber = orgNumber,
                createdAfter = createdAfter,
            )
        } else {
            null
        }
        return LinemanagerRequirementCollection.from(
            list = requirements,
            pageSize = pageSize,
            total = total,
        )
    }
}
