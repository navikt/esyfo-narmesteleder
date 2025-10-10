package no.nav.syfo.narmesteleder.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.db.BehovStatus
import no.nav.syfo.narmesteleder.db.NarmesteLederBehovEntity
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlStatus
import org.slf4j.LoggerFactory

class NarmesteLederLeesahService(
    private val nlDb: NarmestelederDb,
    private val persistLeesahNlBehov: Boolean,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handleNarmesteLederLeesahMessage(nlKafkaMessage: NarmestelederLeesahKafkaMessage) {
        logger.info("Processing NL message with status: ${nlKafkaMessage.status}")

        when (nlKafkaMessage.status) {
            NlStatus.DEAKTIVERT_ARBEIDSTAKER,
            NlStatus.DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING,
            NlStatus.DEAKTIVERT_LEDER,
            NlStatus.DEAKTIVERT_ARBEIDSFORHOLD,
            NlStatus.DEAKTIVERT_NY_LEDER -> {
                handleNlAvbruttMessage(nlKafkaMessage)
            }

            NlStatus.NY_LEDER -> {
                logger.info("Ny leder message received.")
            }

            NlStatus.IDENTENDRING -> {
                logger.info("Identendring message received.")
            }

            NlStatus.UKJENT -> {
                logger.warn("Unknown status received in NL message!")
            }
        }
    }

    private suspend fun handleNlAvbruttMessage(nlKafkaMessage: NarmestelederLeesahKafkaMessage) {
        if (!persistLeesahNlBehov) {
            logger.info("Skipping persistence of NL Behov as configured.")
            return
        }

        withContext(Dispatchers.IO) {
            val id = nlDb.insertNlBehov(
                NarmesteLederBehovEntity(
                    sykmeldtFnr = nlKafkaMessage.fnr,
                    orgnummer = nlKafkaMessage.orgnummer,
                    narmesteLederFnr = nlKafkaMessage.narmesteLederFnr,
                    leesahStatus = nlKafkaMessage.status.name,
                    behovStatus = BehovStatus.RECEIVED
                )
            )
            logger.info("Inserted nærmeste leder-behov with id: $id")
        }
    }
}
