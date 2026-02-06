package no.nav.syfo.sykmelding.kafka

import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.service.BehovSource
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.service.SykmeldingService
import no.nav.syfo.util.logger
import java.time.LocalDate
import java.util.UUID

class SendtSykmeldingHandler(
    private val narmesteLederService: NarmestelederService,
    private val sykmeldingService: SykmeldingService,
) {
    private val logger = logger()

    suspend fun persistSendtSykmelding(message: SendtSykmeldingKafkaMessage) {
        logger.info("Persisting sendt sykmelding with sykmeldingId: ${message.event.sykmeldingId}")
        sykmeldingService.insertOrUpdateSykmelding(message)
    }

    suspend fun requireNarmestelederIfMissing(message: SendtSykmeldingKafkaMessage) {
        logger.info("Handling sendt sykmelding with sykmeldingId: ${message.event.sykmeldingId}")
        if (message.event.brukerSvar?.riktigNarmesteLeder == null) {
            logger.info("No riktigNarmesteLeder answer for sykmeldingId: ${message.event.sykmeldingId}. Creating NL behov...")
            val arbeidsgiver = message.event.arbeidsgiver
                ?: run {
                    logger.error("No arbeidsgiver information for sykmeldingId: ${message.event.sykmeldingId}. Skipping NL behov creation.")
                    return
                }

            narmesteLederService.createNewNlBehov(
                nlBehov = LinemanagerRequirementWrite(
                    employeeIdentificationNumber = message.kafkaMetadata.fnr,
                    orgNumber = arbeidsgiver.orgnummer,
                    behovReason = BehovReason.INGEN_LEDER_REGISTRERT,
                ),
                skipSykmeldingCheck = message.sykmelding.sykmeldingsperioder
                    .any { LocalDate.now() in it.fom..it.tom },
                behovSource = BehovSource(message.kafkaMetadata.sykmeldingId, source = SENDT_SYKMELDING_TOPIC),
                arbeidsgiver = arbeidsgiver,
            )
        } else {
            logger.info("Employee has answered riktigNarmesteLeder for sykmeldingId: ${message.event.sykmeldingId}. No NL behov created.")
        }
    }

    suspend fun handleTombstone(sykmeldingId: String) {
        try {
            val sykmeldingUuid = UUID.fromString(sykmeldingId)
            sykmeldingService.revokeSykmelding(sykmeldingUuid)
        } catch (e: IllegalArgumentException) {
            logger.error("Received tombstone with invalid sykmeldingId format: $sykmeldingId", e)
        }
    }
}
