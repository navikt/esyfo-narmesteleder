package no.nav.syfo.narmesteleder.kafka

import no.nav.syfo.application.exception.toUpstreamUnavailableException
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmesteleder.api.v1.COUNT_CREATE_LINEMANAGER_REQUIREMENT
import no.nav.syfo.narmesteleder.kafka.model.LeesahStatus
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovResult
import no.nav.syfo.util.logger
import org.slf4j.event.Level

private enum class MissingStatusReason {
    UNKNOWN,
    MISSING
}

private val nlMessageStatusSkipped = applicationEvent<MissingStatusReason>(
    name = "nl_message_status_skipped",
    level = Level.WARN,
    message = "Nearest leader message has no recognized status",
    upstream = "kafka",
    fields = mapOf("reason" to { it.name }),
)

class NlBehovLeesahHandler(private val createNarmestelederbehov: CreateNarmestelederbehov) {
    private val logger = logger()

    suspend fun handleByLeesahStatus(command: CreateNarmestelederbehovCommand, status: LeesahStatus?) {
        logger.info("Processing NL message with status: $status")

        when (status) {
            LeesahStatus.DEAKTIVERT_ARBEIDSTAKER,
            LeesahStatus.DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING,
            LeesahStatus.DEAKTIVERT_LEDER,
            LeesahStatus.DEAKTIVERT_PERSONALLEDER,
            LeesahStatus.DEAKTIVERT_LPS
            -> {
                val result = createNarmestelederbehov.execute(command)
                if (result is CreateNarmestelederbehovResult.UpstreamUnavailable) {
                    throw result.failure.toUpstreamUnavailableException()
                }
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
                logger.logEvent(nlMessageStatusSkipped, MissingStatusReason.UNKNOWN)
            }

            null -> {
                logger.logEvent(nlMessageStatusSkipped, MissingStatusReason.MISSING)
            }
        }
    }
}
