package no.nav.syfo.narmesteleder.service

import no.nav.syfo.narmesteleder.db.NarmesteLederBehovEntity
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlStatus
import org.slf4j.LoggerFactory

class NarmesteLederLeesahService(private val narmestelederService: INarmestelederService) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun processNarmesteLederLeesahMessage(nlKafkaMessage: NarmestelederLeesahKafkaMessage) {
        val status =
            NlStatus.fromStatus(nlKafkaMessage.status)
                ?: throw RuntimeException("NL status incorrect or not set")

        logger.info("Processing NL message with status: $status")

        when (status) {
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
        }
    }

    private suspend fun handleNlAvbruttMessage(nlKafkaMessage: NarmestelederLeesahKafkaMessage) {
        val id = narmestelederService.saveAvbrytNarmestelederRelation(
            nlKafkaMessage.toNLBehovEntity()
        )
        logger.info("Inserted nærmeste leder-behov with id: $id")
    }
}

private fun NarmestelederLeesahKafkaMessage.toNLBehovEntity(): NarmesteLederBehovEntity =
    NarmesteLederBehovEntity(
        sykmeldtFnr = this.fnr,
        orgnummer = this.orgnummer,
        narmesteLederFnr = this.narmesteLederFnr,
        status = requireNotNull(this.status) { "Status required for NL Behov" },
    )
