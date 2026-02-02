package no.nav.syfo.narmesteleder.kafka

import no.nav.syfo.narmesteleder.api.v1.COUNT_CREATE_LINEMANAGER_REQUIREMENT
import no.nav.syfo.narmesteleder.api.v1.COUNT_FULFILL_LINEMANAGER_BY_LEGACY_SYSTEM
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.kafka.model.LeesahStatus
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.service.BehovSource
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.util.logger

class NlBehovLeesahHandler(private val narmesteLederService: NarmestelederService) {
    private val logger = logger()

    suspend fun handleByLeesahStatus(nlBehov: LinemanagerRequirementWrite, status: LeesahStatus?, behovSource: BehovSource) {
        logger.info("Processing NL message with status: $status")

        when (status) {
            LeesahStatus.DEAKTIVERT_ARBEIDSTAKER,
            LeesahStatus.DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING,
            LeesahStatus.DEAKTIVERT_LEDER,
            -> {
                narmesteLederService.createNewNlBehov(nlBehov, behovSource = behovSource)
                COUNT_CREATE_LINEMANAGER_REQUIREMENT.increment()
            }

            LeesahStatus.DEAKTIVERT_NY_LEDER -> {
                // Denne sendes fra `Narmesteleder` når de mottar en melding om ny leder
                logger.info("New leader deactivation message received. Expecting new leader assignment.")
            }

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

            null -> {
                logger.warn("Received NL message with null status!")
            }
        }
    }

    suspend fun updateStatusForRequirement(nlKafkaMessage: NarmestelederLeesahKafkaMessage) {
        narmesteLederService.findClosableBehovs(nlKafkaMessage.fnr, nlKafkaMessage.orgnummer)
            .forEach {
                narmesteLederService.updateNlBehov(it, BehovStatus.BEHOV_FULFILLED)
                COUNT_FULFILL_LINEMANAGER_BY_LEGACY_SYSTEM.increment()
            }
    }
}
