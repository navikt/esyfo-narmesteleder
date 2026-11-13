package no.nav.syfo.sykmelding.infrastructure.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetter
import no.nav.syfo.sykmelding.application.SykmeldingsregisterMessage
import no.nav.syfo.sykmelding.application.SykmeldingsregisterPosition
import no.nav.syfo.sykmelding.application.SykmeldingsregisterRejection
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.UUID

private const val CONFIRMED_ANSWER = "JA"

sealed interface ParsedSykmeldingsregisterRecord {
    data class Accepted(val message: SykmeldingsregisterMessage) : ParsedSykmeldingsregisterRecord
    data class Rejected(val deadLetter: SykmeldingsregisterDeadLetter) : ParsedSykmeldingsregisterRecord
}

private sealed interface ParsedKey {
    data class Valid(val sykmeldingId: UUID) : ParsedKey
    data class Invalid(val rejection: SykmeldingsregisterRejection) : ParsedKey
}

/** Parses and validates one record from the sykmeldingsregister topic into a Kafka-free message or a dead letter. */
class SykmeldingsregisterRecordParser(
    private val jacksonMapper: ObjectMapper,
) {
    fun parse(record: ConsumerRecord<String, String?>): ParsedSykmeldingsregisterRecord {
        val position = SykmeldingsregisterPosition(record.partition(), record.offset())
        return when (val key = parseKey(record.key())) {
            is ParsedKey.Valid -> parseMessage(record.value(), key.sykmeldingId, position)
            is ParsedKey.Invalid -> rejected(position, null, key.rejection)
        }
    }

    private fun parseMessage(
        value: String?,
        sykmeldingId: UUID,
        position: SykmeldingsregisterPosition,
    ): ParsedSykmeldingsregisterRecord {
        if (value == null) return ParsedSykmeldingsregisterRecord.Accepted(SykmeldingsregisterMessage.Tombstone(sykmeldingId, position))

        val message = try {
            jacksonMapper.readValue(value, SykmeldingsregisterRecordDto::class.java)
        } catch (e: JsonProcessingException) {
            return rejected(position, sykmeldingId, SykmeldingsregisterRejection.MALFORMED_PAYLOAD, e.fieldPath())
        } ?: return rejected(position, sykmeldingId, SykmeldingsregisterRejection.MALFORMED_PAYLOAD)

        return validateSentSykmelding(message, sykmeldingId, position)
    }

    private fun validateSentSykmelding(
        message: SykmeldingsregisterRecordDto,
        sykmeldingId: UUID,
        position: SykmeldingsregisterPosition,
    ): ParsedSykmeldingsregisterRecord {
        fun invalid(fieldPath: String) = rejected(position, sykmeldingId, SykmeldingsregisterRejection.INVALID_PAYLOAD, fieldPath)

        val fnr = message.kafkaMetadata.fnr
        if (!PersonalIdentificationNumber.isValid(fnr)) return invalid("kafkaMetadata.fnr")
        val arbeidsgiver = message.event.arbeidsgiver ?: return invalid("event.arbeidsgiver")
        if (!OrganizationNumber.isValid(arbeidsgiver.orgnummer)) return invalid("event.arbeidsgiver.orgnummer")
        val perioder = message.sykmelding.sykmeldingsperioder
        if (perioder.isEmpty() || null in perioder) return invalid("sykmelding.sykmeldingsperioder")
        val maxTom = perioder.filterNotNull().maxOf { it.tom }

        return ParsedSykmeldingsregisterRecord.Accepted(
            SykmeldingsregisterMessage.Sent(
                sykmeldingId = sykmeldingId,
                position = position,
                employee = PersonalIdentificationNumber(fnr),
                organizationNumber = OrganizationNumber(arbeidsgiver.orgnummer),
                sentAt = message.event.timestamp,
                maxTom = maxTom,
                narmestelederConfirmed = message.event.brukerSvar?.riktigNarmesteLeder?.svar == CONFIRMED_ANSWER,
            ),
        )
    }
}

private fun rejected(
    position: SykmeldingsregisterPosition,
    sykmeldingId: UUID?,
    rejection: SykmeldingsregisterRejection,
    fieldPath: String? = null,
): ParsedSykmeldingsregisterRecord = ParsedSykmeldingsregisterRecord.Rejected(SykmeldingsregisterDeadLetter(position, sykmeldingId, rejection, fieldPath))

private fun parseKey(key: String?): ParsedKey {
    if (key == null) return ParsedKey.Invalid(SykmeldingsregisterRejection.MISSING_KEY)
    return key.toUuidOrNull()?.let { ParsedKey.Valid(it) } ?: ParsedKey.Invalid(SykmeldingsregisterRejection.INVALID_KEY)
}

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }
    .getOrNull()
    ?.takeIf { it.toString().equals(this, ignoreCase = true) }

/** Uses Jackson's reference chain only; the exception message may contain payload values. */
private fun JsonProcessingException.fieldPath(): String? = (this as? JsonMappingException)
    ?.path
    ?.joinToString(".") { it.fieldName ?: "[${it.index}]" }
    ?.takeIf { it.isNotEmpty() }
