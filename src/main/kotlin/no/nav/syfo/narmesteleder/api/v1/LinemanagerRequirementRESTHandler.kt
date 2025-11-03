package no.nav.syfo.narmesteleder.api.v1

import java.util.*
import no.nav.syfo.application.auth.Principal
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
import no.nav.syfo.narmesteleder.service.ValidateNarmesteLederException
import no.nav.syfo.narmesteleder.service.ValidationService

class LinemanagerRequirementRESTHandler(
    private val narmesteLederService: NarmestelederService,
    private val validationService: ValidationService,
    private val narmestelederKafkaService: NarmestelederKafkaService
) {
    suspend fun handleUpdatedRequirement(
        manager: Manager,
        requirementId: UUID,
        principal: Principal
    ) {
        try {
            val employee = narmesteLederService.getEmployeeByRequirementId(requirementId)
            val linemanager = Linemanager(
                employeeIdentificationNumber = employee.nationalIdentificationNumber,
                orgnumber = employee.orgnumber,
                manager = manager
            )
            val linemanagerActors = validationService.validateLinemanager(
                linemanager,
                principal
            )

            narmestelederKafkaService.sendNarmesteLederRelasjon(
                linemanager,
                linemanagerActors,
                NlResponseSource.leder, // TODO: Hva skal denne stå til?
            )
            narmesteLederService.updateNlBehov(
                manager = manager,
                requirementId = requirementId,
                behovStatus = BehovStatus.COMPLETED
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

    suspend fun handleGetLinemanagerRequirement(requirementId: UUID, principal: Principal): LinemanagerRequirementRead =
        try {
            narmesteLederService.getLinemanagerRequirementReadById(requirementId).also {
                validationService.validateGetNlBehov(principal, it)
            }
        } catch (e: LinemanagerRequirementNotFoundException) {
            throw ApiErrorException.NotFoundException("LinemanagerRequirement", e)
        } catch (e: ValidateNarmesteLederException) {
            throw ApiErrorException.ForbiddenException("You don't have access to this LinemanagerRequirement", e)
        } catch (e: ApiErrorException) {
            throw e
        } catch (e: Exception) {
            throw ApiErrorException.InternalServerErrorException(
                "Something went wrong while fetching LinemanagerRequirement",
                e
            )
        }
}
