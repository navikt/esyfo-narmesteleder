package no.nav.syfo.sykmelding.infrastructure.kafka

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.platform.kafka.jacksonMapper
import no.nav.syfo.sykmelding.application.SykmeldingsregisterMessage
import no.nav.syfo.sykmelding.application.SykmeldingsregisterPosition
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.Arbeidsgiver
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterRecordDto.Sykmeldingsperiode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class SykmeldingsregisterRecordParserTest :
    FunSpec({
        val parser = SykmeldingsregisterRecordParser(jacksonMapper())

        test("maps a sent sykmelding to a Kafka-free message with its position") {
            val message = sykmeldingsregisterRecordDto(
                fnr = FNR,
                arbeidsgiver = Arbeidsgiver(orgnummer = ORGNUMMER),
                sykmeldingsperioder = listOf(
                    Sykmeldingsperiode(tom = LocalDate.parse("2026-10-31")),
                    Sykmeldingsperiode(tom = LocalDate.parse("2026-11-20")),
                    Sykmeldingsperiode(tom = LocalDate.parse("2026-11-05")),
                ),
                svar = "JA",
            )

            parser.parse(sykmeldingsregisterRecord(partition = 2, offset = 41, value = message.toJson())) shouldBe accepted(
                SykmeldingsregisterMessage.Sent(
                    sykmeldingId = SYKMELDING_ID,
                    position = SykmeldingsregisterPosition(partition = 2, offset = 41),
                    employee = PersonalIdentificationNumber(FNR),
                    organizationNumber = OrganizationNumber(ORGNUMMER),
                    sentAt = OffsetDateTime.parse("2026-11-02T09:15:30Z"),
                    maxTom = LocalDate.parse("2026-11-20"),
                    narmestelederConfirmed = true,
                ),
            )
        }

        listOf("NEI", null).forEach { svar ->
            test("the answer ${svar ?: "missing"} is not a confirmation") {
                val parsed = parser.parse(sykmeldingsregisterRecord(value = sykmeldingsregisterRecordDto(svar = svar).toJson()))

                parsed.shouldBeInstanceOf<ParsedSykmeldingsregisterRecord.Accepted>()
                    .message.shouldBeInstanceOf<SykmeldingsregisterMessage.Sent>()
                    .narmestelederConfirmed shouldBe false
            }
        }

        test("a record without value is a tombstone") {
            parser.parse(sykmeldingsregisterRecord(partition = 1, offset = 9, value = null)) shouldBe accepted(
                SykmeldingsregisterMessage.Tombstone(SYKMELDING_ID, SykmeldingsregisterPosition(partition = 1, offset = 9)),
            )
        }

        listOf(
            InvalidRecord("missing key", key = null, value = validJson(), sykmeldingId = null, reason = "MISSING_KEY"),
            InvalidRecord("invalid key", key = "not-a-uuid", value = validJson(), sykmeldingId = null, reason = "INVALID_KEY"),
            InvalidRecord("non-canonical key", key = "1-1-1-1-1", value = validJson(), sykmeldingId = null, reason = "INVALID_KEY"),
            InvalidRecord("not JSON", value = "{$FNR", reason = "MALFORMED_PAYLOAD"),
            InvalidRecord("JSON null", value = "null", reason = "MALFORMED_PAYLOAD"),
            InvalidRecord(
                "missing required field",
                value = """{"kafkaMetadata":{"sykmeldingId":"$SYKMELDING_ID","fnr":"$FNR"}}""",
                reason = "MALFORMED_PAYLOAD: event",
            ),
            InvalidRecord(
                "kafkaMetadata.sykmeldingId differs from key",
                value = sykmeldingsregisterRecordDto(kafkaMetadataSykmeldingId = OTHER_SYKMELDING_ID).toJson(),
                reason = "INVALID_PAYLOAD: kafkaMetadata.sykmeldingId",
            ),
            InvalidRecord(
                "event.sykmeldingId differs from key",
                value = sykmeldingsregisterRecordDto(eventSykmeldingId = OTHER_SYKMELDING_ID).toJson(),
                reason = "INVALID_PAYLOAD: event.sykmeldingId",
            ),
            InvalidRecord(
                "invalid fnr",
                value = sykmeldingsregisterRecordDto(fnr = "1234567890X").toJson(),
                reason = "INVALID_PAYLOAD: kafkaMetadata.fnr",
            ),
            InvalidRecord(
                "missing arbeidsgiver",
                value = sykmeldingsregisterRecordDto(arbeidsgiver = null).toJson(),
                reason = "INVALID_PAYLOAD: event.arbeidsgiver",
            ),
            InvalidRecord(
                "invalid orgnummer",
                value = sykmeldingsregisterRecordDto(arbeidsgiver = Arbeidsgiver(orgnummer = "12345")).toJson(),
                reason = "INVALID_PAYLOAD: event.arbeidsgiver.orgnummer",
            ),
            InvalidRecord(
                "no perioder",
                value = sykmeldingsregisterRecordDto(sykmeldingsperioder = emptyList()).toJson(),
                reason = "INVALID_PAYLOAD: sykmelding.sykmeldingsperioder",
            ),
            InvalidRecord(
                "null periode",
                value = sykmeldingsregisterRecordDto(sykmeldingsperioder = listOf(Sykmeldingsperiode(tom = LocalDate.parse("2026-11-20")), null)).toJson(),
                reason = "INVALID_PAYLOAD: sykmelding.sykmeldingsperioder",
            ),
        ).forEach { invalid ->
            test("a record with ${invalid.name} is rejected with its reason, without payload values") {
                val parsed = parser.parse(sykmeldingsregisterRecord(partition = 0, offset = 5, key = invalid.key, value = invalid.value))

                val deadLetter = parsed.shouldBeInstanceOf<ParsedSykmeldingsregisterRecord.Rejected>().deadLetter
                assertSoftly {
                    deadLetter.position shouldBe SykmeldingsregisterPosition(0, 5)
                    deadLetter.sykmeldingId shouldBe invalid.sykmeldingId
                    deadLetter.reason shouldBe invalid.reason
                }
            }
        }
    })

private val OTHER_SYKMELDING_ID: UUID = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d")
private const val FNR = "01019012345"
private const val ORGNUMMER = "987654321"

private data class InvalidRecord(
    val name: String,
    val key: String? = SYKMELDING_ID.toString(),
    val value: String,
    val sykmeldingId: UUID? = SYKMELDING_ID,
    val reason: String,
)

private fun accepted(message: SykmeldingsregisterMessage) = ParsedSykmeldingsregisterRecord.Accepted(message)

private fun validJson(): String = sykmeldingsregisterRecordDto(fnr = FNR).toJson()
