package no.nav.syfo.sykmelding.retention.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import no.nav.syfo.sykmelding.retention.application.RecordingSykmeldingRetentionRepository
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionMetrics
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

class DeleteOldSykmeldingerTaskTest :
    FunSpec({
        test("deletes old sykmeldinger when the task executes") {
            val repository = RecordingSykmeldingRetentionRepository()
            val task = DeleteOldSykmeldingerTask(
                deleteOldSykmeldinger = DeleteOldSykmeldinger(
                    repository = repository,
                    clock = Clock.fixed(Instant.parse("2026-03-17T10:00:00Z"), ZoneOffset.UTC),
                    metrics = SykmeldingRetentionMetrics(SimpleMeterRegistry()),
                ),
                interval = 24.hours,
            )

            task.execute()

            repository.requests.map { (cutoff, _) -> cutoff } shouldBe listOf(LocalDate.parse("2025-03-17"))
        }
    })
