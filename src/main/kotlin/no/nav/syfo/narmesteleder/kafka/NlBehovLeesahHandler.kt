package no.nav.syfo.narmesteleder.kafka

import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.kafka.model.LeesahStatus
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.util.logger

class NlBehovLeesahHandler(private val narmesteLederService: NarmestelederService) {
    private val logger = logger()

    suspend fun handleByLeesahStatus(nlBehov: LinemanagerRequirementWrite, status: LeesahStatus) {
        logger.info("Processing NL message with status: $status")

        when (status) {
            LeesahStatus.DEAKTIVERT_ARBEIDSTAKER,
            LeesahStatus.DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING,
            LeesahStatus.DEAKTIVERT_LEDER,
            LeesahStatus.DEAKTIVERT_NY_LEDER
                -> narmesteLederService.createNewNlBehov(nlBehov)

            LeesahStatus.DEAKTIVERT_ARBEIDSFORHOLD -> {
                logger.info("Arbeidsforhold deactivated message received.")
            }

            LeesahStatus.NY_LEDER -> {
                logger.info("Ny leder message received.")
            }

            LeesahStatus.IDENTENDRING -> {
                logger.info("Identendring message received.")
            }

            LeesahStatus.UKJENT -> {
                logger.warn("Unknown status received in NL message!")
            }
        }
    }
}
