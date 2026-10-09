package no.nav.syfo.narmestelederrelasjon.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.ident.PersonIdent
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PersistNarmestelederrelasjonerFromLeesahUseCaseTest :
    FunSpec({
        lateinit var repository: RecordingLeesahNarmestelederrelasjonRepository
        lateinit var metrics: RecordingNarmestelederRegisterMetrics
        lateinit var useCase: PersistNarmestelederrelasjonerFromLeesahUseCase

        beforeTest {
            repository = RecordingLeesahNarmestelederrelasjonRepository()
            metrics = RecordingNarmestelederRegisterMetrics()
            useCase = PersistNarmestelederrelasjonerFromLeesahUseCase(repository, metrics)
        }

        test("stores valid relations with each person of both parties once and returns the records") {
            val records = listOf(
                record(relasjon(sykmeldtFnr = "12345678901", narmestelederFnr = "10987654321")),
                record(relasjon(sykmeldtFnr = "10987654321", narmestelederFnr = "12345678901")),
                record(relasjon(sykmeldtFnr = "11111111111", narmestelederFnr = "11111111111")),
            )

            useCase.execute(records) shouldBe records

            val call = repository.calls.single()
            call.relasjoner shouldBe records.map { it.relasjon.validated() }
            call.persons shouldBe listOf("12345678901", "10987654321", "11111111111").map(::PersonIdent)
            metrics.upserted shouldBe listOf(3)
            metrics.invalid shouldBe 0
        }

        test("skips invalid records without blocking valid records in the same batch") {
            val valid = record(relasjon())
            val invalid = record(relasjon(sykmeldtFnr = "123"))

            useCase.execute(listOf(valid, invalid)) shouldBe listOf(valid)

            repository.calls.single().relasjoner shouldBe listOf(valid.relasjon.validated())
            metrics.upserted shouldBe listOf(1)
            metrics.invalid shouldBe 1
        }

        test("observes only valid employment-ended revocations after upsert and metrics, continuing after a failure") {
            val now = Instant.parse("2026-06-15T12:00:00Z")
            val observedAt = now.minusSeconds(60)
            val failing = record(relasjon(sourceEmploymentRevocationAt = observedAt))
            val succeeding = record(relasjon(sourceEmploymentRevocationAt = observedAt))
            val otherChange = record(relasjon())
            val invalid = record(relasjon(sykmeldtFnr = "123", sourceEmploymentRevocationAt = observedAt))
            val validRecords = listOf(failing, succeeding, otherChange)
            val reconciliations = RecordingEmploymentReconciliationRepository()
            val observationMetrics = RecordingEmploymentCheckMetrics()
            val observedIds = mutableListOf<UUID>()
            val observationRepository = object : EmploymentReconciliationRepository by reconciliations {
                override fun recordSourceRevocation(narmesteLederId: UUID, observedAt: Instant, now: Instant): Boolean {
                    repository.calls.single().relasjoner shouldBe validRecords.map { it.relasjon.validated() }
                    metrics.upserted shouldBe listOf(3)
                    observedIds += narmesteLederId
                    if (narmesteLederId == failing.relasjon.narmestelederId) error("Observation unavailable")
                    return reconciliations.recordSourceRevocation(narmesteLederId, observedAt, now)
                }
            }
            val observingUseCase = PersistNarmestelederrelasjonerFromLeesahUseCase(
                repository,
                metrics,
                RecordSourceEmploymentRevocationUseCase(observationRepository, Clock.fixed(now, ZoneOffset.UTC), observationMetrics),
            )

            observingUseCase.execute(validRecords + invalid) shouldBe validRecords

            observedIds shouldBe listOf(failing.relasjon.narmestelederId, succeeding.relasjon.narmestelederId)
            reconciliations.observations shouldBe listOf(Triple(succeeding.relasjon.narmestelederId, observedAt, now))
            observationMetrics.observations shouldBe listOf(SourceObservationOutcome.FAILED, SourceObservationOutcome.RECORDED)
            metrics.invalid shouldBe 1
        }

        test("persists employment-ended revocations normally when the observation dependency is null") {
            val revoked = record(relasjon(sourceEmploymentRevocationAt = Instant.parse("2026-06-15T12:00:00Z")))
            val disabledUseCase = PersistNarmestelederrelasjonerFromLeesahUseCase(
                repository,
                metrics,
                recordSourceEmploymentRevocation = null,
            )

            disabledUseCase.execute(listOf(revoked)) shouldBe listOf(revoked)

            repository.calls.single().relasjoner shouldBe listOf(revoked.relasjon.validated())
            metrics.upserted shouldBe listOf(1)
            metrics.invalid shouldBe 0
        }

        listOf(
            relasjon(sykmeldtFnr = "123") to "fnr must be exactly 11 digits",
            relasjon(orgnummer = "12345678a") to "orgnummer must be exactly 9 digits",
            relasjon(narmestelederFnr = "1234567890a") to "narmesteLederFnr must be exactly 11 digits",
            relasjon(narmestelederTelefonnummer = "1".repeat(256)) to "narmesteLederTelefonnummer exceeds max length",
            relasjon(narmestelederEpost = "a".repeat(256)) to "narmesteLederEpost exceeds max length",
        ).forEach { (relasjon, reason) ->
            test("skips a record where $reason and does not call the repository") {
                val logged = captureInvalidRecordLogs { useCase.execute(listOf(record(relasjon))).shouldBeEmpty() }

                logged.single().fields()["validation_reason"] shouldBe reason
                repository.calls.shouldBeEmpty()
                metrics.upserted.shouldBeEmpty()
                metrics.invalid shouldBe 1
            }
        }

        test("accepts text fields at the maximum length") {
            val atLimit = record(relasjon(narmestelederTelefonnummer = "1".repeat(255), narmestelederEpost = "a".repeat(255)))

            useCase.execute(listOf(atLimit)) shouldBe listOf(atLimit)
        }

        test("logs only the register record id and Kafka position of an invalid record") {
            val invalid = record(relasjon(sykmeldtFnr = "123"), partition = 3, offset = 42)

            val event = captureInvalidRecordLogs { useCase.execute(listOf(invalid)) }.single()

            val fields = event.fields()
            fields["event_type"] shouldBe "nl_register_record_invalid"
            fields["narmesteleder_id"] shouldBe invalid.relasjon.narmestelederId.toString()
            fields["partition"] shouldBe 3
            fields["offset"] shouldBe 42L
            val logged = event.formattedMessage + fields
            logged shouldNotContain invalid.relasjon.sykmeldtFnr
            logged shouldNotContain invalid.relasjon.orgnummer
            logged shouldNotContain invalid.relasjon.narmestelederFnr
        }

        test("propagates repository failures without recording upserts") {
            val failingUseCase = PersistNarmestelederrelasjonerFromLeesahUseCase(
                repository = { _, _ -> error("database down") },
                metrics = metrics,
            )

            runCatching { failingUseCase.execute(listOf(record(relasjon()))) }
                .exceptionOrNull()?.message shouldBe "database down"
            metrics.upserted.shouldBeEmpty()
        }
    })

private fun captureInvalidRecordLogs(block: () -> Unit): List<ILoggingEvent> {
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    val logger = LoggerFactory.getLogger(PersistNarmestelederrelasjonerFromLeesahUseCase::class.java) as Logger
    val previousLevel = logger.level
    logger.level = Level.WARN
    logger.addAppender(appender)
    try {
        block()
        return appender.list.toList()
    } finally {
        logger.detachAppender(appender)
        logger.level = previousLevel
        appender.stop()
    }
}

private fun ILoggingEvent.fields() = keyValuePairs.associate { it.key to it.value }

private fun record(
    relasjon: LeesahNarmestelederrelasjon,
    partition: Int = 0,
    offset: Long = 0,
) = LeesahNarmestelederrelasjonRecord(partition = partition, offset = offset, relasjon = relasjon)

private fun relasjon(
    sykmeldtFnr: String = "12345678901",
    orgnummer: String = "123456789",
    narmestelederFnr: String = "10987654321",
    narmestelederTelefonnummer: String = "12345678",
    narmestelederEpost: String = "leder@example.com",
    sourceEmploymentRevocationAt: Instant? = null,
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
    sourceEmploymentRevocationAt = sourceEmploymentRevocationAt,
)
