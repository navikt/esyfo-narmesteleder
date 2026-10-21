package no.nav.syfo.sykmelding.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovResult
import no.nav.syfo.narmestelederrelasjon.application.HasActiveNarmestelederrelasjon
import no.nav.syfo.platform.api.toUpstreamUnavailableException
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.observability.logger
import org.slf4j.event.Level
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

private val sickLeaveEmployerMissing = applicationEvent(
    name = "sick_leave_employer_missing",
    level = Level.ERROR,
    message = "Cannot process sick leave message because employer information is missing",
    fields = sickLeaveFields,
)

private val sickLeaveMessageSkipped = applicationEvent(
    name = "sick_leave_message_skipped",
    level = Level.WARN,
    message = "Sick leave message was skipped because an identifier is invalid",
    fields = sickLeaveFields + ("reason" to { it: SickLeaveLogDetails -> it.reason?.name }),
)

private val sickLeaveBehovSkippedActiveRelation = applicationEvent(
    name = "sick_leave_behov_skipped_active_relation",
    level = Level.INFO,
    message = "No nearest leader need created because the employee already has an active relation",
    fields = sickLeaveFields,
)

class SendtSykmeldingHandler(
    private val createNarmestelederbehov: CreateNarmestelederbehov,
    private val sykmeldingService: SykmeldingService,
    private val narmestelederBruddService: NarmestelederBruddService,
    private val hasActiveNarmestelederrelasjon: HasActiveNarmestelederrelasjon,
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
        logger.info("No riktigNarmesteLeder answer for sykmeldingId: ${message.event.sykmeldingId}. Checking NL behov...")
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

        // Keep this check here, not in CreateNarmestelederbehov: the Leesah deactivation flow also
        // creates behov, and the local relation register may not yet reflect the deactivation.
        if (hasActiveNarmestelederrelasjon.execute(
                PersonalIdentificationNumber(message.kafkaMetadata.fnr),
                OrganizationNumber(arbeidsgiver.orgnummer),
            )
        ) {
            COUNT_CREATE_BEHOV_SKIPPED_HAS_ACTIVE_NARMESTELEDER.increment()
            logger.logEvent(sickLeaveBehovSkippedActiveRelation, SickLeaveLogDetails(message.logId(), SickLeaveAction.CREATE_BEHOV))
            return
        }

        val result = createNarmestelederbehov.execute(message.toCreateNarmestelederbehovCommand(arbeidsgiver))
        if (result is CreateNarmestelederbehovResult.UpstreamUnavailable) {
            throw result.failure.toUpstreamUnavailableException()
        }
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
