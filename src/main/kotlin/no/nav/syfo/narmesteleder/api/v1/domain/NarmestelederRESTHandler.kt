package no.nav.syfo.narmesteleder.api.v1.domain

import java.util.UUID
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.NlBehovRead
import no.nav.syfo.narmesteleder.domain.NlBehovUpdate
import no.nav.syfo.narmesteleder.domain.NlBehovWrite
import no.nav.syfo.narmesteleder.service.BehovNotFoundException
import no.nav.syfo.narmesteleder.service.HovedenhetNotFoundException
import no.nav.syfo.narmesteleder.service.NarmesteLederService

class NarmestelederRESTHandler(private val narmesteLederService: NarmesteLederService) {
    suspend fun handleCreateNlBehov(nlBehovUpdate: NlBehovWrite) {
        try {
            narmesteLederService.createNewNlBehov(nlBehovUpdate)
        } catch (e: HovedenhetNotFoundException) {
            throw ApiErrorException.BadRequestException("Hovedenhet not found for sykemeldt", e)
        }
    }

    suspend fun handleUpdatedNl(nlBehovUpdate: NlBehovUpdate) {
        try {
            narmesteLederService.updateNlBehov(nlBehovUpdate, BehovStatus.PENDING)
        } catch (e: HovedenhetNotFoundException) {
            throw ApiErrorException.NotFoundException("Hovedenhet not found", e)
        } catch (e: BehovNotFoundException) {
            throw ApiErrorException.NotFoundException("Narmesteleder-behov not found", e)
        }
    }

    suspend fun handleGetNlBehov(nlBehovId: UUID) {
        try {
            narmesteLederService.getNlBehovById(nlBehovId)
        } catch (e: BehovNotFoundException) {
            throw ApiErrorException.NotFoundException("Narmesteleder-behov not found", e)
        }
    }

    suspend fun handleGetAllNlBehov(personIdent: String, orgNummer: String) {
        narmesteLederService.findAllNlBehov(personIdent, orgNummer)
    }
}

fun NarmestelederBehovEntity.toNlBehovRead(): NlBehovRead = NlBehovRead(
    id = this.id!!,
    sykmeldtFnr = this.sykmeldtFnr,
    orgnummer = this.orgnummer,
    narmesteLederFnr = this.narmestelederFnr,
)
