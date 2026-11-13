package no.nav.syfo.sykmelding.infrastructure.kafka

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.platform.kafka.jacksonMapper
import no.nav.syfo.sykmelding.application.SENDT_SYKMELDING_TOPIC
import no.nav.syfo.sykmelding.application.SykmeldingsregisterMessage
import no.nav.syfo.sykmelding.application.SykmeldingsregisterPosition
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class SykmeldingsregisterRecordParserTest :
    FunSpec({
        val parser = SykmeldingsregisterRecordParser(jacksonMapper())

        test("maps a sent sykmelding to a Kafka-free message with its position") {
            val message = sendtSykmelding(
                sykmeldingsperioder = listOf(
                    SykmeldingsperiodeDto(tom = LocalDate.parse("2026-11-20")),
                    SykmeldingsperiodeDto(tom = LocalDate.parse("2026-10-31")),
                ),
                svar = "JA",
            )

            parser.parse(record(partition = 2, offset = 41, value = message.json())) shouldBe accepted(
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

        context("an answer other than JA or no answer is not a confirmation") {
            withData(
                nameFn = { it ?: "no answer" },
                "NEI",
                null,
            ) { svar ->
                val parsed = parser.parse(record(value = sendtSykmelding(svar = svar).json()))

                ((parsed as ParsedSykmeldingsregisterRecord.Accepted).message as SykmeldingsregisterMessage.Sent)
                    .narmestelederConfirmed shouldBe false
            }
        }

        test("a record without value is a tombstone") {
            parser.parse(record(partition = 1, offset = 9, value = null)) shouldBe accepted(
                SykmeldingsregisterMessage.Tombstone(SYKMELDING_ID, SykmeldingsregisterPosition(partition = 1, offset = 9)),
            )
        }

        context("an invalid record is rejected with its reason, without payload values") {
            withData(
                nameFn = { it.name },
                InvalidRecord("missing key", key = null, value = validJson(), sykmeldingId = null, reason = "MISSING_KEY"),
                InvalidRecord("invalid key", key = "not-a-uuid", value = validJson(), sykmeldingId = null, reason = "INVALID_KEY"),
                InvalidRecord("not JSON", value = "{$FNR", reason = "MALFORMED_PAYLOAD"),
                InvalidRecord(
                    "missing required field",
                    value = """{"kafkaMetadata":{"fnr":"$FNR"}}""",
                    reason = "MALFORMED_PAYLOAD: event",
                ),
                InvalidRecord(
                    "invalid fnr",
                    value = sendtSykmelding(fnr = "1234567890X").json(),
                    reason = "INVALID_PAYLOAD: kafkaMetadata.fnr",
                ),
                InvalidRecord(
                    "missing arbeidsgiver",
                    value = sendtSykmelding(arbeidsgiver = null).json(),
                    reason = "INVALID_PAYLOAD: event.arbeidsgiver",
                ),
                InvalidRecord(
                    "invalid orgnummer",
                    value = sendtSykmelding(arbeidsgiver = ArbeidsgiverDto(orgnummer = "12345")).json(),
                    reason = "INVALID_PAYLOAD: event.arbeidsgiver.orgnummer",
                ),
                InvalidRecord(
                    "no perioder",
                    value = sendtSykmelding(sykmeldingsperioder = emptyList()).json(),
                    reason = "INVALID_PAYLOAD: sykmelding.sykmeldingsperioder",
                ),
                InvalidRecord(
                    "null periode",
                    value = sendtSykmelding(sykmeldingsperioder = listOf(SykmeldingsperiodeDto(tom = LocalDate.parse("2026-11-20")), null)).json(),
                    reason = "INVALID_PAYLOAD: sykmelding.sykmeldingsperioder",
                ),
            ) { invalid ->
                val parsed = parser.parse(record(partition = 0, offset = 5, key = invalid.key, value = invalid.value))

                val deadLetter = (parsed as ParsedSykmeldingsregisterRecord.Rejected).deadLetter
                (deadLetter.position to deadLetter.sykmeldingId to deadLetter.reason) shouldBe
                    (SykmeldingsregisterPosition(0, 5) to invalid.sykmeldingId to invalid.reason)
            }
        }
    })

private val SYKMELDING_ID: UUID = UUID.fromString("5f0c1e2a-3b4c-4d5e-8f60-718293a4b5c6")
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

private fun sendtSykmelding(
    fnr: String = FNR,
    arbeidsgiver: ArbeidsgiverDto? = ArbeidsgiverDto(orgnummer = ORGNUMMER),
    sykmeldingsperioder: List<SykmeldingsperiodeDto?> = listOf(SykmeldingsperiodeDto(tom = LocalDate.parse("2026-11-20"))),
    svar: String? = null,
) = SykmeldingsregisterRecordDto(
    kafkaMetadata = KafkaMetadataDto(fnr = fnr),
    event = EventDto(
        timestamp = OffsetDateTime.parse("2026-11-02T10:15:30+01:00"),
        arbeidsgiver = arbeidsgiver,
        brukerSvar = BrukerSvarDto(riktigNarmesteLeder = svar?.let { RiktigNarmesteLederDto(svar = it) }),
    ),
    sykmelding = SykmeldingDto(sykmeldingsperioder = sykmeldingsperioder),
)

private fun validJson(): String = sendtSykmelding().json()

private fun SykmeldingsregisterRecordDto.json(): String = jacksonMapper().writeValueAsString(this)

private fun record(
    partition: Int = 0,
    offset: Long = 0,
    key: String? = SYKMELDING_ID.toString(),
    value: String?,
) = ConsumerRecord<String, String?>(SENDT_SYKMELDING_TOPIC, partition, offset, key, value)
