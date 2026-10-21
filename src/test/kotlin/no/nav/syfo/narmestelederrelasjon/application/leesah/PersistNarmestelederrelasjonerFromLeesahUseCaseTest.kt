package no.nav.syfo.narmestelederrelasjon.application.leesah

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.EmploymentReconciliationRepository
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.RecordSourceEmploymentRevocationUseCase
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.RecordingEmploymentCheckMetrics
import no.nav.syfo.narmestelederrelasjon.application.employmentcheck.RecordingEmploymentReconciliationRepository
import no.nav.syfo.platform.observability.ProductionLogRecord
import no.nav.syfo.platform.observability.withProductionLogs
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
            call.persons shouldBe listOf("12345678901", "10987654321", "11111111111").map(::PersonalIdentificationNumber)
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

        test("notes employment-ended revocations from valid records, after the upsert") {
            val now = Instant.parse("2026-06-15T12:00:00Z")
            val revoked = record(relasjon(sourceEmploymentRevocationAt = now))
            val otherChange = record(relasjon())
            val invalid = record(relasjon(sykmeldtFnr = "123", sourceEmploymentRevocationAt = now))
            val reconciliations = RecordingEmploymentReconciliationRepository()
            val checkedAfterUpsert = object : EmploymentReconciliationRepository by reconciliations {
                override fun recordSourceRevocation(narmesteLederId: UUID, revokedAt: Instant): Boolean {
                    // The upsert has committed before the observation runs.
                    repository.calls.size shouldBe 1
                    return reconciliations.recordSourceRevocation(narmesteLederId, revokedAt)
                }
            }
            val observation = RecordSourceEmploymentRevocationUseCase(
                checkedAfterUpsert,
                Clock.fixed(now, ZoneOffset.UTC),
                RecordingEmploymentCheckMetrics(),
            )

            PersistNarmestelederrelasjonerFromLeesahUseCase(repository, metrics, observation)
                .execute(listOf(revoked, otherChange, invalid)) shouldBe listOf(revoked, otherChange)

            reconciliations.observed.keys shouldBe setOf(revoked.relasjon.narmestelederId)
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

                logged.single()["validation_reason"] shouldBe reason
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

            val fields = event.fields
            fields["event_type"] shouldBe "nl_register_record_invalid"
            fields["narmesteleder_id"] shouldBe invalid.relasjon.narmestelederId.toString()
            fields["partition"] shouldBe 3
            fields["offset"] shouldBe 42
            val logged = event.serialized
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

private suspend fun captureInvalidRecordLogs(block: () -> Unit): List<ProductionLogRecord> = withProductionLogs(PersistNarmestelederrelasjonerFromLeesahUseCase::class.java, Level.WARN) { capture ->
    block()
    capture.logs
}

private fun record(relasjon: LeesahNarmestelederrelasjon, partition: Int = 0, offset: Long = 0) = LeesahNarmestelederrelasjonRecord(partition = partition, offset = offset, relasjon = relasjon)

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
