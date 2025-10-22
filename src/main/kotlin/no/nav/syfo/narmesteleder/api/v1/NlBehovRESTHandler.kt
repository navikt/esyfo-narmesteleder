package no.nav.syfo.narmesteleder.api.v1

import java.util.*
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.NlBehovRead
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.narmesteleder.service.BehovNotFoundException
import no.nav.syfo.narmesteleder.service.HovedenhetNotFoundException
import no.nav.syfo.narmesteleder.service.NarmesteLederService
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.ValidationService

class NarmestelederRESTHandler(
    private val narmesteLederService: NarmesteLederService,
    private val validationService: ValidationService,
    private val narmestelederKafkaService: NarmestelederKafkaService
) {
    suspend fun handleUpdatedNl(nlRelasjonerWrite: NarmesteLederRelasjonerWrite, behovId: UUID, principal: Principal) {
        try {
            val nlAktorer = validationService.validateNarmesteleder(
                nlRelasjonerWrite,
                principal
            )
            validationService.validateNarmesteleder(nlRelasjonerWrite, principal)

            narmestelederKafkaService.sendNarmesteLederRelation(
                nlRelasjonerWrite,
                nlAktorer,
                NlResponseSource.LPS, // TODO: Hvordan bestemme source her?
            )
            narmesteLederService.updateNlBehov(nlRelasjonerWrite.toNlbehovUpdate(behovId), BehovStatus.PENDING)
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
}

fun NarmestelederBehovEntity.toNlBehovRead(): NlBehovRead = NlBehovRead(
    id = this.id!!,
    sykmeldtFnr = this.sykmeldtFnr,
    orgnummer = this.orgnummer,
    narmesteLederFnr = this.narmestelederFnr,
)
