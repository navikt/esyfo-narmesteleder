package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import no.nav.syfo.TestDB
import no.nav.syfo.narmestelederbehov.application.DialogReconciliationSettings
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookupResult
import no.nav.syfo.narmestelederbehov.application.ReconcileNarmestelederbehovDialogUseCase
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedDialogReconciliationRepository
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerDialogReconciliationMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.insertNarmestelederbehov
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

class ConfigureDialogReconciliationTest :
    FunSpec({
        test("a pod starts without promotion and shutdown cancels its in-flight reconciliation without acknowledging") {
            TestDB.clearAllData()
            val now = Instant.parse("2030-01-01T10:00:00Z")
            insertNarmestelederbehov(created = now.minusSeconds(30))
            val repository = ExposedDialogReconciliationRepository(TestDB.exposedDatabase)
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val metrics = MicrometerDialogReconciliationMetrics(repository, SimpleMeterRegistry(), clock)
            val settings = DialogReconciliationSettings()
            val worker = ReconcileNarmestelederbehovDialogUseCase(
                repository = repository,
                creation = { _, _, _ ->
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
                completion = { error("must not complete") },
                expiry = { _, _ -> error("must not expire") },
                inspection = { error("must not inspect") },
                personLookup = { EmployeePersonLookupResult.NotFound },
                clock = clock,
                metrics = metrics,
                settings = settings,
            )
            testApplication {
                application {
                    install(Koin) {
                        modules(
                            module {
                                single { worker }
                                single { metrics }
                                single { settings }
                            }
                        )
                    }
                    configureDialogReconciliation(OtherEnvironmentProperties.createForLocal())
                    monitor.raise(ServerReady, environment)
                }
                startApplication()
                withTimeout(5.seconds) { entered.await() }
            }
            withTimeout(5.seconds) { cancelled.await() }
            repository.stats().pending shouldBe 1
            requireNotNull(repository.claim(now.plusSeconds(61), 60.seconds)).attempts shouldBe 0
        }

        test("a disabled worker needs no processing dependencies and does not start at server readiness") {
            val metrics = MicrometerDialogReconciliationMetrics(
                ExposedDialogReconciliationRepository(TestDB.exposedDatabase),
                SimpleMeterRegistry(),
            )
            testApplication {
                application {
                    install(Koin) { modules(module { single { metrics } }) }
                    configureDialogReconciliation(OtherEnvironmentProperties.createForLocal().copy(dialogReconciliationEnabled = false))
                    monitor.raise(ServerReady, environment)
                }
                startApplication()
            }
        }
    })
