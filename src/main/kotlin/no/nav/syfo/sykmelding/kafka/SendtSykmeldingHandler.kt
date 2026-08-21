package no.nav.syfo.sykmelding.kafka

import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.service.BehovSource
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.service.NarmestelederBruddService
import no.nav.syfo.sykmelding.service.SykmeldingService
import no.nav.syfo.util.logger
import java.time.LocalDate
import java.util.UUID

class SendtSykmeldingHandler(
    private val narmesteLederService: NarmestelederService,
    private val sykmeldingService: SykmeldingService,
    private val narmestelederBruddService: NarmestelederBruddService,
) {
    private val logger = logger()

    suspend fun handleSykmeldingBatch(records: List<SykmeldingRecord>) {
        if (records.isEmpty()) return
        logger.info("Persisting batch of ${records.size} sykmelding records")
        sykmeldingService.processBatch(records)
    }

    suspend fun handleNarmestelederbehov(
        message: SendtSykmeldingKafkaMessage,
        kafkaPartition: Int = 0,
        kafkaOffset: Long = 0,
    ) {
        logger.info("Handling sendt sykmelding with sykmeldingId: ${message.event.sykmeldingId}")
        val riktigNarmesteLeder = message.event.brukerSvar?.riktigNarmesteLeder
        if (riktigNarmesteLeder == null) {
            createNarmestelederBehov(message)
        } else if (riktigNarmesteLeder.svar == NEGATIVE_ANSWER) {
            revokeNarmestelederRelation(message, kafkaPartition, kafkaOffset)
        } else {
            logger.info("Employee has answered riktigNarmesteLeder for sykmeldingId: ${message.event.sykmeldingId}. No NL behov created.")
        }
    }

    private suspend fun createNarmestelederBehov(message: SendtSykmeldingKafkaMessage) {
        logger.info("No riktigNarmesteLeder answer for sykmeldingId: ${message.event.sykmeldingId}. Creating NL behov...")
        val arbeidsgiver = message.event.arbeidsgiver
            ?: run {
                logger.error("No arbeidsgiver information for sykmeldingId: ${message.event.sykmeldingId}. Skipping NL behov creation.")
                return
            }

        if (!message.kafkaMetadata.fnr.isDigitsWithLength(FNR_LENGTH)) {
            logger.warn("Invalid fnr in sendt sykmelding with sykmeldingId: ${message.event.sykmeldingId}. Skipping NL behov creation.")
            return
        }
        if (!arbeidsgiver.orgnummer.isDigitsWithLength(ORGNUMMER_LENGTH)) {
            logger.warn("Invalid orgnummer in sendt sykmelding with sykmeldingId: ${message.event.sykmeldingId}. Skipping NL behov creation.")
            return
        }

        narmesteLederService.createNewNlBehov(
            nlBehov = LinemanagerRequirementWrite(
                employeeIdentificationNumber = PersonalIdentificationNumber(message.kafkaMetadata.fnr),
                orgNumber = OrganizationNumber(arbeidsgiver.orgnummer),
                behovReason = BehovReason.INGEN_LEDER_REGISTRERT,
            ),
            skipSykmeldingCheck = message.sykmelding.sykmeldingsperioder
                .any { LocalDate.now() in it.fom..it.tom },
            behovSource = BehovSource(message.kafkaMetadata.sykmeldingId, source = SENDT_SYKMELDING_TOPIC),
            arbeidsgiver = arbeidsgiver,
        )
    }

    private suspend fun revokeNarmestelederRelation(
        message: SendtSykmeldingKafkaMessage,
        kafkaPartition: Int,
        kafkaOffset: Long,
    ) {
        val arbeidsgiver = message.event.arbeidsgiver
            ?: run {
                logger.error("No arbeidsgiver information for sykmeldingId: ${message.event.sykmeldingId}. Skipping NL relation revoke.")
                return
            }

        if (!message.kafkaMetadata.fnr.isDigitsWithLength(FNR_LENGTH)) {
            logger.warn("Invalid fnr in sendt sykmelding with sykmeldingId: ${message.event.sykmeldingId}. Skipping NL relation revoke.")
            return
        }
        if (!arbeidsgiver.orgnummer.isDigitsWithLength(ORGNUMMER_LENGTH)) {
            logger.warn("Invalid orgnummer in sendt sykmelding with sykmeldingId: ${message.event.sykmeldingId}. Skipping NL relation revoke.")
            return
        }

        narmestelederBruddService.revokeFromSendtSykmelding(
            sykmeldingId = UUID.fromString(message.event.sykmeldingId),
            fnr = message.kafkaMetadata.fnr,
            orgnummer = arbeidsgiver.orgnummer,
            kafkaPartition = kafkaPartition,
            kafkaOffset = kafkaOffset,
        )
    }

    private fun String.isDigitsWithLength(length: Int): Boolean = this.length == length && all(Char::isDigit)

    companion object {
        private const val FNR_LENGTH = 11
        private const val ORGNUMMER_LENGTH = 9
        private const val NEGATIVE_ANSWER = "NEI"
    }
}
