package no.nav.syfo.narmesteleder.api.v1

import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.exception.HovedenhetNotFoundException
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.util.logger
import java.time.Instant
import java.util.*

class LinemanagerRequirementRESTHandler(
    private val narmesteLederService: NarmestelederService,
    private val validationService: ValidationService,
    private val narmestelederKafkaService: NarmestelederKafkaService,
    private val altinnTilgangerService: AltinnTilgangerService,
) {
    companion object {
        val logger = logger()
    }
    suspend fun handleUpdatedRequirement(
        manager: Manager,
        requirementId: UUID,
        principal: Principal
    ) {
        try {
            val employee = narmesteLederService.getEmployeeByRequirementId(requirementId)
            val linemanager = Linemanager(
                employeeIdentificationNumber = employee.nationalIdentificationNumber,
                orgNumber = employee.orgNumber,
                lastName = employee.lastName,
                manager = manager
            )
            val linemanagerActors = validationService.validateLinemanager(
                linemanager = linemanager,
                principal = principal,
                validateEmployeeLastName = false
            )

            narmestelederKafkaService.sendNarmesteLederRelasjon(
                linemanager,
                linemanagerActors,
                NlResponseSource.getSourceFrom(principal, linemanager)
            )
            narmesteLederService.updateNlBehov(
                requirementId = requirementId,
                behovStatus = BehovStatus.BEHOV_FULFILLED
            )
        } catch (e: HovedenhetNotFoundException) {
            throw ApiErrorException.NotFoundException("Main entity not found", e)
        } catch (e: LinemanagerRequirementNotFoundException) {
            throw ApiErrorException.NotFoundException("A LinemanagerRequirement was not found", e)
        } catch (e: ApiErrorException) {
            throw e
        } catch (e: Exception) {
            throw ApiErrorException.InternalServerErrorException("Internal server error", e)
        }
    }

    suspend fun handleGetLinemanagerRequirement(requirementId: UUID, principal: Principal): LinemanagerRequirementRead = try {
        narmesteLederService.getLinemanagerRequirementReadById(requirementId).let {
            val altinnTilgang = if (principal is UserPrincipal) {
                altinnTilgangerService.getAltinnTilgangForOrgnr(
                    principal,
                    it.orgNumber
                )
            } else {
                null
            }

            validationService.validateGetNlBehov(principal, it, altinnTilgang)
            it.copy(orgName = altinnTilgang?.navn)
        }
    } catch (e: LinemanagerRequirementNotFoundException) {
        throw ApiErrorException.NotFoundException("LinemanagerRequirement", e)
    } catch (e: ApiErrorException) {
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
        orgNumber: String,
        principal: Principal
    ): List<LinemanagerRequirementRead> {
        validationService.validateLinemanagerRequirementCollectionAccess(principal, orgNumber)
        logger.info("Validation successful for fetching LinemanagerRequirement collection for orgNumber: $orgNumber")
        return narmesteLederService.getNlBehovList(
            pageSize = pageSize,
            createdAfter = createdAfter,
            orgNumber = orgNumber
        )
    }
}
