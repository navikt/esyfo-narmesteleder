package no.nav.syfo.narmestelederrelasjon.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.UUID

class PersistNarmestelederrelasjonerFromLeesahUseCaseTest :
    DescribeSpec({
        lateinit var repository: RecordingLeesahNarmestelederrelasjonRepository
        lateinit var metrics: RecordingNarmestelederRegisterMetrics
        lateinit var useCase: PersistNarmestelederrelasjonerFromLeesahUseCase

        beforeTest {
            repository = RecordingLeesahNarmestelederrelasjonRepository()
            metrics = RecordingNarmestelederRegisterMetrics()
            useCase = PersistNarmestelederrelasjonerFromLeesahUseCase(repository, metrics)
        }

        describe("execute") {
            it("stores valid relations with the persons of both parties and returns the valid records") {
                val first = record(offset = 1, relasjon = relasjon(sykmeldtFnr = "12345678901", narmestelederFnr = "10987654321"))
                val second = record(offset = 2, relasjon = relasjon(sykmeldtFnr = "11111111111", narmestelederFnr = "22222222222"))

                val result = useCase.execute(listOf(first, second))

                result shouldBe listOf(first, second)
                repository.calls.single().relasjoner shouldBe listOf(first.relasjon, second.relasjon)
                repository.calls.single().personFnrs shouldBe listOf("12345678901", "10987654321", "11111111111", "22222222222")
                metrics.upserted shouldBe listOf(2)
                metrics.invalid shouldBe 0
            }

            it("registers each person only once") {
                val records = listOf(
                    record(offset = 1, relasjon = relasjon(sykmeldtFnr = "12345678901", narmestelederFnr = "12345678901")),
                    record(offset = 2, relasjon = relasjon(sykmeldtFnr = "12345678901", narmestelederFnr = "10987654321")),
                    record(offset = 3, relasjon = relasjon(sykmeldtFnr = "10987654321", narmestelederFnr = "12345678901")),
                )

                useCase.execute(records)

                repository.calls.single().personFnrs shouldBe listOf("12345678901", "10987654321")
            }

            it("skips invalid records without blocking valid records in the same batch") {
                val valid = record(offset = 1, relasjon = relasjon())
                val invalid = record(offset = 2, relasjon = relasjon(sykmeldtFnr = "123", narmestelederFnr = "22222222222"))

                val result = useCase.execute(listOf(valid, invalid))

                result shouldBe listOf(valid)
                repository.calls.single().relasjoner shouldBe listOf(valid.relasjon)
                repository.calls.single().personFnrs shouldBe listOf(valid.relasjon.sykmeldtFnr, valid.relasjon.narmestelederFnr)
                metrics.upserted shouldBe listOf(1)
                metrics.invalid shouldBe 1
            }

            it("does not call the repository when every record is invalid") {
                val records = listOf(
                    record(offset = 1, relasjon = relasjon(sykmeldtFnr = "123")),
                    record(offset = 2, relasjon = relasjon(orgnummer = "123")),
                    record(offset = 3, relasjon = relasjon(narmestelederFnr = "1234567890a")),
                    record(offset = 4, relasjon = relasjon(narmestelederTelefonnummer = "1".repeat(256))),
                    record(offset = 5, relasjon = relasjon(narmestelederEpost = "a".repeat(256))),
                )

                val result = useCase.execute(records)

                result.shouldBeEmpty()
                repository.calls.shouldBeEmpty()
                metrics.upserted.shouldBeEmpty()
                metrics.invalid shouldBe 5
            }

            it("accepts text fields at the maximum length") {
                val atLimit = record(
                    offset = 1,
                    relasjon = relasjon(narmestelederTelefonnummer = "1".repeat(255), narmestelederEpost = "a".repeat(255)),
                )

                useCase.execute(listOf(atLimit)) shouldBe listOf(atLimit)
            }

            it("logs only the register record id, Kafka position and reason for an invalid record") {
                val invalid = record(partition = 3, offset = 42, relasjon = relasjon(sykmeldtFnr = "123"))
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                val logger = LoggerFactory.getLogger(PersistNarmestelederrelasjonerFromLeesahUseCase::class.java) as Logger
                val previousLevel = logger.level
                logger.level = Level.WARN
                logger.addAppender(appender)
                try {
                    useCase.execute(listOf(invalid))

                    val event = appender.list.single()
                    val fields = event.keyValuePairs.associate { it.key to it.value }
                    fields["event_type"] shouldBe "nl_register_record_invalid"
                    fields["narmesteleder_id"] shouldBe invalid.relasjon.narmestelederId.toString()
                    fields["partition"] shouldBe 3
                    fields["offset"] shouldBe 42L
                    fields["validation_reason"] shouldBe "fnr must be exactly 11 digits"
                    val logged = event.formattedMessage + fields.toString()
                    logged.contains(invalid.relasjon.sykmeldtFnr) shouldBe false
                    logged.contains(invalid.relasjon.orgnummer) shouldBe false
                    logged.contains(invalid.relasjon.narmestelederFnr) shouldBe false
                } finally {
                    logger.detachAppender(appender)
                    logger.level = previousLevel
                    appender.stop()
                }
            }

            it("propagates repository failures without recording upserts") {
                val failingUseCase = PersistNarmestelederrelasjonerFromLeesahUseCase(
                    repository = { _, _ -> error("database down") },
                    metrics = metrics,
                )

                runCatching { failingUseCase.execute(listOf(record(offset = 1, relasjon = relasjon()))) }
                    .exceptionOrNull()?.message shouldBe "database down"
                metrics.upserted.shouldBeEmpty()
            }
        }
    })

private fun record(partition: Int = 0, offset: Long, relasjon: LeesahNarmestelederrelasjon) = LeesahNarmestelederrelasjonRecord(
    partition = partition,
    offset = offset,
    relasjon = relasjon,
)

private fun relasjon(
    sykmeldtFnr: String = "12345678901",
    orgnummer: String = "123456789",
    narmestelederFnr: String = "10987654321",
    narmestelederTelefonnummer: String = "12345678",
    narmestelederEpost: String = "leder@example.com",
) = LeesahNarmestelederrelasjon(
    narmestelederId = UUID.randomUUID(),
    sykmeldtFnr = sykmeldtFnr,
    orgnummer = orgnummer,
    narmestelederFnr = narmestelederFnr,
    narmestelederTelefonnummer = narmestelederTelefonnummer,
    narmestelederEpost = narmestelederEpost,
    aktivFom = LocalDate.of(2024, 1, 1),
    aktivTom = null,
    arbeidsgiverForskutterer = true,
)
