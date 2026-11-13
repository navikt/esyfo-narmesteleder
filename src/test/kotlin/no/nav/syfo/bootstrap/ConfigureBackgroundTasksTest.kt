package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.application.RecordingExpiryRepository
import no.nav.syfo.narmestelederrelasjon.application.relationperson.EnrichPendingRelationPersonsUseCase
import no.nav.syfo.narmestelederrelasjon.application.relationperson.FakeBulkPersonLookup
import no.nav.syfo.narmestelederrelasjon.application.relationperson.InMemoryRelationPersonRepository
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.platform.scheduling.LeaderChange
import no.nav.syfo.platform.scheduling.LeaderChangeEvent
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import no.nav.syfo.sykmelding.retention.application.RecordingSykmeldingRetentionRepository
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionMetrics
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val clock = Clock.fixed(Instant.parse("2026-03-17T10:00:00Z"), ZoneOffset.UTC)

class ConfigureBackgroundTasksTest :
    FunSpec({
        test("a promoted pod starts every task with the configured use cases") {
            val tasks = RecordingTasks()

            tasks.promote {
                assertSoftly {
                    tasks.relationPersons.findPendingLimits.size shouldBe 1
                    tasks.expiry.calls.map { it.first } shouldBe listOf(LocalDate.parse("2026-03-01"))
                    tasks.retention.requests.map { it.first } shouldBe listOf(LocalDate.parse("2025-03-17"))
                }
            }
        }
    })

private class RecordingTasks {
    val relationPersons = InMemoryRelationPersonRepository()
    val expiry = RecordingExpiryRepository()
    val retention = RecordingSykmeldingRetentionRepository()

    private val useCases = module {
        single { EnrichPendingRelationPersonsUseCase(relationPersons, FakeBulkPersonLookup()) }
        single { ExpireNarmestelederbehovUseCase(expiry, get(), clock, pauseBetweenBatches = Duration.ZERO) }
        single { DeleteOldSykmeldinger(retention, clock, SykmeldingRetentionMetrics(SimpleMeterRegistry())) }
    }

    fun promote(startedTasksHaveRun: () -> Unit) = testApplication {
        application {
            install(Koin) {
                modules(
                    useCases,
                    tasksModule(
                        narmestelederbehovExpirySettings = NarmestelederbehovExpirySettings(daysAfterTom = 16),
                        maintenanceTaskSettings = MaintenanceTaskSettings(interval = 1.hours),
                        personEnrichmentTaskSettings = PersonEnrichmentTaskSettings(pollingInterval = 1.hours),
                    ),
                )
            }
            configureBackgroundTasks()
            monitor.raise(LeaderChangeEvent, LeaderChange.Promoted)
        }
        startApplication()
        eventually(5.seconds) { startedTasksHaveRun() }
    }
}
