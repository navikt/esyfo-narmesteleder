package no.nav.syfo.narmesteleder.service.validators

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.sykmelding.exposed.IActiveSykmeldingRepository
import no.nav.syfo.util.logger

class SickLeaveValidator(
    private val activeSykmeldingRepository: IActiveSykmeldingRepository,
) {
    companion object {
        val logger = logger()
    }

    suspend fun validateActiveSickLeave(fnr: String, orgnummer: String) {
        if (!activeSykmeldingRepository.hasActiveSykmelding(fnr, orgnummer)) {
            val message = "No active sick leave found for the given organization number: $orgnummer"
            logger.warn(message)
            throw ApiErrorException.BadRequestException(
                errorMessage = message,
                type = ErrorType.NO_ACTIVE_SICK_LEAVE,
            )
        }
    }
}
