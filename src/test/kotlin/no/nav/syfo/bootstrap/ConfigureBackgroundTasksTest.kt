package no.nav.syfo.bootstrap

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.syfo.narmestelederbehov.application.CompleteDialogsFixture
import no.nav.syfo.narmestelederbehov.application.ExpireDialogsFixture
import no.nav.syfo.narmestelederbehov.application.ExpireNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.application.RecordingExpiryRepository
import no.nav.syfo.narmestelederbehov.application.SendDialogsFixture
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenTaskSettings
import no.nav.syfo.narmestelederbehov.infrastructure.SendDialogTask
import no.nav.syfo.narmestelederbehov.infrastructure.UpdateDialogTask
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
        test("a promoted pod starts every enabled task with the configured use cases") {
            val tasks = RecordingTasks()

            tasks.promote(
                dialogportenTaskSettings = DialogportenTaskSettings(enabled = true),
                maintenanceTaskSettings = MaintenanceTaskSettings(enabled = true, interval = 1.hours),
                personEnrichmentTaskSettings = PersonEnrichmentTaskSettings(enabled = true, pollingInterval = 1.hours),
            ) {
                tasks.sendDialogs.queries.size shouldBe 1
                tasks.completeDialogs.repository.fulfilledQueries.size shouldBe 1
                tasks.expireDialogs.repository.expiryQueries shouldBe 1
                tasks.relationPersons.findPendingLimits.size shouldBe 1
                tasks.expiry.calls.map { it.first } shouldBe listOf(LocalDate.parse("2026-03-01"))
                tasks.retention.requests.map { it.first } shouldBe listOf(LocalDate.parse("2025-03-17"))
            }
        }

        test("a promoted pod starts only maintenance tasks when Dialogporten tasks are disabled") {
            val tasks = RecordingTasks()

            tasks.promote(
                dialogportenTaskSettings = DialogportenTaskSettings(enabled = false),
                maintenanceTaskSettings = MaintenanceTaskSettings(enabled = true, interval = 1.hours),
                personEnrichmentTaskSettings = PersonEnrichmentTaskSettings(enabled = true, pollingInterval = 1.hours),
            ) {
                tasks.expiry.calls.size shouldBe 1
                tasks.retention.requests.size shouldBe 1
            }

            tasks.sendDialogs.queries.shouldBeEmpty()
            tasks.completeDialogs.repository.fulfilledQueries.shouldBeEmpty()
            tasks.relationPersons.findPendingLimits.shouldBeEmpty()
        }

        test("a promoted pod starts only Dialogporten tasks when person enrichment and maintenance are disabled") {
            val tasks = RecordingTasks()

            tasks.promote(
                dialogportenTaskSettings = DialogportenTaskSettings(enabled = true),
                maintenanceTaskSettings = MaintenanceTaskSettings(enabled = false, interval = 1.hours),
                personEnrichmentTaskSettings = PersonEnrichmentTaskSettings(enabled = false, pollingInterval = 1.hours),
            ) {
                tasks.sendDialogs.queries.size shouldBe 1
                tasks.completeDialogs.repository.fulfilledQueries.size shouldBe 1
            }

            tasks.relationPersons.findPendingLimits.shouldBeEmpty()
            tasks.expiry.calls.shouldBeEmpty()
            tasks.retention.requests.shouldBeEmpty()
        }
    })

private class RecordingTasks {
    val sendDialogs = SendDialogsFixture(emptyList())
    val completeDialogs = CompleteDialogsFixture()
    val expireDialogs = ExpireDialogsFixture()
    val relationPersons = InMemoryRelationPersonRepository()
    val expiry = RecordingExpiryRepository()
    val retention = RecordingSykmeldingRetentionRepository()

    private val useCases = module {
        single { SendDialogTask(sendDialogs.useCase) }
        single { UpdateDialogTask(completeDialogs.useCase, expireDialogs.useCase, 1.hours) }
        single { EnrichPendingRelationPersonsUseCase(relationPersons, FakeBulkPersonLookup()) }
        single { ExpireNarmestelederbehovUseCase(expiry, get(), clock, pauseBetweenBatches = Duration.ZERO) }
        single { DeleteOldSykmeldinger(retention, clock, SykmeldingRetentionMetrics(SimpleMeterRegistry())) }
    }

    fun promote(
        dialogportenTaskSettings: DialogportenTaskSettings,
        maintenanceTaskSettings: MaintenanceTaskSettings,
        personEnrichmentTaskSettings: PersonEnrichmentTaskSettings,
        startedTasksHaveRun: () -> Unit,
    ) = testApplication {
        application {
            install(Koin) {
                modules(
                    useCases,
                    tasksModule(
                        narmestelederbehovExpirySettings = NarmestelederbehovExpirySettings(daysAfterTom = 16),
                        maintenanceTaskSettings = maintenanceTaskSettings,
                        personEnrichmentTaskSettings = personEnrichmentTaskSettings,
                    ),
                )
            }
            configureBackgroundTasks(dialogportenTaskSettings, maintenanceTaskSettings, personEnrichmentTaskSettings)
            monitor.raise(LeaderChangeEvent, LeaderChange.Promoted)
        }
        startApplication()
        eventually(5.seconds) { startedTasksHaveRun() }
    }
}
