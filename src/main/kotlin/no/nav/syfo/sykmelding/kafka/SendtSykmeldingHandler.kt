package no.nav.syfo.sykmelding.kafka

import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.logEvent
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
import org.slf4j.event.Level
import java.time.LocalDate
import java.util.UUID

private enum class SickLeaveAction {
    CREATE_BEHOV,
    REVOKE_RELATION
}
private enum class SickLeaveSkipReason {
    PERSON_ID_INVALID,
    ORG_NUMBER_INVALID
}
private data class SickLeaveLogDetails(
    val sykmeldingId: String?,
    val action: SickLeaveAction,
    val reason: SickLeaveSkipReason? = null,
)

private val sickLeaveFields: Map<String, (SickLeaveLogDetails) -> Any?> = mapOf(
    "sykmelding_id" to { it.sykmeldingId },
    "action" to { it.action.name },
)

private val sickLeaveEmployerMissing = applicationEvent<SickLeaveLogDetails>(
    name = "sick_leave_employer_missing",
    level = Level.ERROR,
    message = "Cannot process sick leave message because employer information is missing",
    fields = sickLeaveFields,
)

private val sickLeaveMessageSkipped = applicationEvent<SickLeaveLogDetails>(
    name = "sick_leave_message_skipped",
    level = Level.WARN,
    message = "Sick leave message was skipped because an identifier is invalid",
    fields = sickLeaveFields + ("reason" to { it: SickLeaveLogDetails -> it.reason?.name }),
)

class SendtSykmeldingHandler(
    private val narmesteLederService: NarmestelederService,
    private val sykmeldingService: SykmeldingService,
    private val narmestelederBruddService: NarmestelederBruddService,
) {
    private val logger = logger()
    private fun SendtSykmeldingKafkaMessage.logId(): String? = runCatching {
        UUID.fromString(event.sykmeldingId).toString()
    }.getOrNull()

    private fun logEmployerMissing(message: SendtSykmeldingKafkaMessage, action: SickLeaveAction) {
        logger.logEvent(sickLeaveEmployerMissing, SickLeaveLogDetails(message.logId(), action))
    }

    private fun logSkipped(message: SendtSykmeldingKafkaMessage, action: SickLeaveAction, reason: SickLeaveSkipReason) {
        logger.logEvent(sickLeaveMessageSkipped, SickLeaveLogDetails(message.logId(), action, reason))
    }

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
                logEmployerMissing(message, SickLeaveAction.CREATE_BEHOV)
                return
            }

        if (!message.kafkaMetadata.fnr.isDigitsWithLength(FNR_LENGTH)) {
            logSkipped(message, SickLeaveAction.CREATE_BEHOV, SickLeaveSkipReason.PERSON_ID_INVALID)
            return
        }
        if (!arbeidsgiver.orgnummer.isDigitsWithLength(ORGNUMMER_LENGTH)) {
            logSkipped(message, SickLeaveAction.CREATE_BEHOV, SickLeaveSkipReason.ORG_NUMBER_INVALID)
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
                logEmployerMissing(message, SickLeaveAction.REVOKE_RELATION)
                return
            }

        if (!message.kafkaMetadata.fnr.isDigitsWithLength(FNR_LENGTH)) {
            logSkipped(message, SickLeaveAction.REVOKE_RELATION, SickLeaveSkipReason.PERSON_ID_INVALID)
            return
        }
        if (!arbeidsgiver.orgnummer.isDigitsWithLength(ORGNUMMER_LENGTH)) {
            logSkipped(message, SickLeaveAction.REVOKE_RELATION, SickLeaveSkipReason.ORG_NUMBER_INVALID)
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
