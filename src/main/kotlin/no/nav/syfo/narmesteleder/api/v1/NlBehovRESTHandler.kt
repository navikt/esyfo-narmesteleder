package no.nav.syfo.narmesteleder.api.v1

import io.micrometer.core.instrument.config.validate.ValidationException
import java.util.*
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.NlBehovRead
import no.nav.syfo.narmesteleder.exception.BehovNotFoundException
import no.nav.syfo.narmesteleder.exception.HovedenhetNotFoundException
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.ValidateNarmesteLederException

class NlBehovRESTHandler(
    private val narmesteLederService: NarmestelederService,
    private val validationService: ValidationService,
    private val narmestelederKafkaService: NarmestelederKafkaService
) {
    suspend fun handleUpdatedNl(nlRelasjonerWrite: NarmesteLederRelasjonerWrite, behovId: UUID, principal: Principal) {
        try {
            val nlAktorer = validationService.validateNarmesteleder(
                nlRelasjonerWrite,
                principal
            )

            narmestelederKafkaService.sendNarmesteLederRelation(
                nlRelasjonerWrite,
                nlAktorer,
                NlResponseSource.leder, // TODO: Hva skal denne stå til?
            )
            narmesteLederService.updateNlBehov(nlRelasjonerWrite.toNlbehovUpdate(behovId), BehovStatus.PENDING)
        } catch (e: HovedenhetNotFoundException) {
            throw ApiErrorException.NotFoundException("Hovedenhet not found", e)
        } catch (e: BehovNotFoundException) {
            throw ApiErrorException.NotFoundException("Narmesteleder-behov not found", e)
        } catch (e: ApiErrorException) {
            throw e
        } catch (e: Exception) {
            throw ApiErrorException.InternalServerErrorException("Internal server error", e)
        }
    }

    suspend fun handleGetNlBehov(nlBehovId: UUID, principal: Principal): NlBehovRead = try {
        narmesteLederService.getNlBehovById(nlBehovId).also {
            validationService.validateGetNlBehov(principal, it)
        }
    } catch (e: BehovNotFoundException) {
        throw ApiErrorException.NotFoundException("Narmesteleder-behov not found", e)
    } catch (e: ValidateNarmesteLederException) {
        throw ApiErrorException.ForbiddenException("You don't have access to this behov", e)
    } catch (e: Exception) {
        throw ApiErrorException.InternalServerErrorException(
            "Something went wrong while fetching Narmesteleder-behov",
            e
        )
    }
}
