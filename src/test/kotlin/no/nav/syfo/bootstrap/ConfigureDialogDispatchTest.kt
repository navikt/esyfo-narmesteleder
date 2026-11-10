package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
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
import no.nav.syfo.narmestelederbehov.DialogDispatchLoopSettings
import no.nav.syfo.narmestelederbehov.application.DispatchNarmestelederbehovDialogUseCase
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookupResult
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedDialogDispatchRepository
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerDialogDispatchMetrics
import no.nav.syfo.narmestelederbehov.infrastructure.insertNarmestelederbehov
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

class ConfigureDialogDispatchTest :
    FunSpec({
        test("a pod starts without promotion and shutdown cancels its in-flight reconciliation without acknowledging") {
            TestDB.clearAllData()
            val now = Instant.parse("2030-01-01T10:00:00Z")
            insertNarmestelederbehov(created = now.minusSeconds(30))
            val repository = ExposedDialogDispatchRepository(TestDB.exposedDatabase)
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val metrics = MicrometerDialogDispatchMetrics(repository, SimpleMeterRegistry(), clock)
            val worker = DispatchNarmestelederbehovDialogUseCase(
                dispatchQueue = repository,
                dispatchWork = repository,
                dialogCreation = { _, _, _ ->
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                },
                dialogCompletion = { error("must not complete") },
                dialogExpiry = { _, _ -> error("must not expire") },
                dialogExpiryInspection = { error("must not inspect") },
                employeePersonLookup = { EmployeePersonLookupResult.NotFound },
                clock = object : kotlin.time.Clock {
                    override fun now() = now.toKotlinInstant()
                },
                dispatchMetrics = metrics,
            )
            testApplication {
                application {
                    install(Koin) {
                        modules(
                            module {
                                single { worker }
                                single { metrics }
                            }
                        )
                    }
                    configureDialogDispatch(DialogDispatchLoopSettings(enabled = true, interval = 5.seconds))
                    monitor.raise(ServerReady, environment)
                }
                startApplication()
                withTimeout(5.seconds) { entered.await() }
            }
            withTimeout(5.seconds) { cancelled.await() }
            val pending = repository.stats().pending
            val reclaimed = requireNotNull(repository.claim(now.plusSeconds(61), 60.seconds))
            assertSoftly {
                pending shouldBe 1
                reclaimed.attempts shouldBe 0
            }
        }

        test("a disabled worker needs no processing dependencies and does not start at server readiness") {
            val metrics = MicrometerDialogDispatchMetrics(
                ExposedDialogDispatchRepository(TestDB.exposedDatabase),
                SimpleMeterRegistry(),
            )
            testApplication {
                application {
                    install(Koin) { modules(module { single { metrics } }) }
                    configureDialogDispatch(DialogDispatchLoopSettings(enabled = false, interval = 5.seconds))
                    monitor.raise(ServerReady, environment)
                }
                startApplication()
            }
        }
    })
