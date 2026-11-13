package no.nav.syfo.sykmelding.infrastructure.kafka

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetter
import no.nav.syfo.sykmelding.application.SykmeldingsregisterMessage
import no.nav.syfo.sykmelding.application.SykmeldingsregisterPosition
import no.nav.syfo.sykmelding.application.SykmeldingsregisterRejection
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.time.LocalDate
import java.time.OffsetDateTime
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

/** What the record value says, independent of where the record was read. */
private sealed interface PayloadResult {
    data object Tombstone : PayloadResult

    data class Sent(
        val employee: PersonalIdentificationNumber,
        val organizationNumber: OrganizationNumber,
        val sentAt: OffsetDateTime,
        val maxTom: LocalDate,
        val narmestelederConfirmed: Boolean,
    ) : PayloadResult

    data class Invalid(val rejection: SykmeldingsregisterRejection, val fieldPath: String? = null) : PayloadResult
}

/** Parses and validates one record from the sykmeldingsregister topic into a Kafka-free message or a dead letter. */
class SykmeldingsregisterRecordParser(
    private val jacksonMapper: ObjectMapper,
) {
    fun parse(record: ConsumerRecord<String, String?>): ParsedSykmeldingsregisterRecord {
        val position = SykmeldingsregisterPosition(record.partition(), record.offset())
        val sykmeldingId = when (val key = parseKey(record.key())) {
            is ParsedKey.Valid -> key.sykmeldingId
            is ParsedKey.Invalid -> return ParsedSykmeldingsregisterRecord.Rejected(SykmeldingsregisterDeadLetter(position, null, key.rejection))
        }

        return when (val payload = parsePayload(record.value(), sykmeldingId)) {
            PayloadResult.Tombstone -> ParsedSykmeldingsregisterRecord.Accepted(SykmeldingsregisterMessage.Tombstone(sykmeldingId, position))
            is PayloadResult.Sent -> ParsedSykmeldingsregisterRecord.Accepted(payload.toMessage(sykmeldingId, position))
            is PayloadResult.Invalid -> ParsedSykmeldingsregisterRecord.Rejected(
                SykmeldingsregisterDeadLetter(position, sykmeldingId, payload.rejection, payload.fieldPath),
            )
        }
    }

    private fun parsePayload(value: String?, sykmeldingId: UUID): PayloadResult {
        if (value == null) return PayloadResult.Tombstone

        val message = try {
            jacksonMapper.readValue<SykmeldingsregisterRecordDto?>(value)
                ?: return PayloadResult.Invalid(SykmeldingsregisterRejection.MALFORMED_PAYLOAD)
        } catch (e: JsonProcessingException) {
            return PayloadResult.Invalid(SykmeldingsregisterRejection.MALFORMED_PAYLOAD, e.fieldPath())
        }

        return message.validateToPayloadResult(sykmeldingId)
    }
}

private fun SykmeldingsregisterRecordDto.validateToPayloadResult(sykmeldingId: UUID): PayloadResult {
    fun invalid(fieldPath: String) = PayloadResult.Invalid(SykmeldingsregisterRejection.INVALID_PAYLOAD, fieldPath)

    if (kafkaMetadata.sykmeldingId != sykmeldingId) return invalid("kafkaMetadata.sykmeldingId")
    if (event.sykmeldingId != sykmeldingId) return invalid("event.sykmeldingId")
    val fnr = kafkaMetadata.fnr
    if (!PersonalIdentificationNumber.isValid(fnr)) return invalid("kafkaMetadata.fnr")
    val arbeidsgiver = event.arbeidsgiver ?: return invalid("event.arbeidsgiver")
    if (!OrganizationNumber.isValid(arbeidsgiver.orgnummer)) return invalid("event.arbeidsgiver.orgnummer")
    val perioder = sykmelding.sykmeldingsperioder
    if (perioder.isEmpty() || null in perioder) return invalid("sykmelding.sykmeldingsperioder")

    return PayloadResult.Sent(
        employee = PersonalIdentificationNumber(fnr),
        organizationNumber = OrganizationNumber(arbeidsgiver.orgnummer),
        sentAt = event.timestamp,
        maxTom = perioder.filterNotNull().maxOf { it.tom },
        narmestelederConfirmed = event.brukerSvar?.riktigNarmesteLeder?.svar == CONFIRMED_ANSWER,
    )
}

private fun PayloadResult.Sent.toMessage(sykmeldingId: UUID, position: SykmeldingsregisterPosition) = SykmeldingsregisterMessage.Sent(
    sykmeldingId = sykmeldingId,
    position = position,
    employee = employee,
    organizationNumber = organizationNumber,
    sentAt = sentAt,
    maxTom = maxTom,
    narmestelederConfirmed = narmestelederConfirmed,
)

private fun parseKey(key: String?): ParsedKey {
    if (key == null) return ParsedKey.Invalid(SykmeldingsregisterRejection.MISSING_KEY)
    return key.toUuidOrNull()?.let { ParsedKey.Valid(it) } ?: ParsedKey.Invalid(SykmeldingsregisterRejection.INVALID_KEY)
}

private fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this).takeIf { it.toString().equals(this, ignoreCase = true) }
} catch (_: IllegalArgumentException) {
    null
}

/** Uses Jackson's reference chain only; the exception message may contain payload values. */
private fun JsonProcessingException.fieldPath(): String? = (this as? JsonMappingException)
    ?.path
    ?.joinToString(".") { it.fieldName ?: "[${it.index}]" }
    ?.takeIf { it.isNotEmpty() }
