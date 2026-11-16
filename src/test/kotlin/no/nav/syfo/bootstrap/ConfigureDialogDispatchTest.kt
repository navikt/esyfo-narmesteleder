package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.DialogDispatchLoopSettings
import no.nav.syfo.narmestelederbehov.application.DialogDispatchState
import no.nav.syfo.narmestelederbehov.application.DialogDispatchStats
import no.nav.syfo.narmestelederbehov.application.DispatchNarmestelederbehovDialogUseCase
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookupResult
import no.nav.syfo.narmestelederbehov.application.FakeQueue
import no.nav.syfo.narmestelederbehov.application.FakeWork
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerDialogDispatchMetrics
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

class ConfigureDialogDispatchTest :
    FunSpec({
        val noStats = DialogDispatchStats(pending = 0, parked = 0, oldestPending = null)

        test("a pod starts without promotion and shutdown cancels its in-flight reconciliation without acknowledging") {
            val queue = FakeQueue()
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val worker = DispatchNarmestelederbehovDialogUseCase(
                dispatchQueue = queue,
                dispatchWork = FakeWork(
                    DialogDispatchState(
                        employee = Employee(PersonalIdentificationNumber("12345678901"), OrganizationNumber("123456789")),
                        status = BehovStatus.BEHOV_CREATED,
                        dialogId = null,
                        createStarted = false,
                        intendedExpiry = null,
                        expiredAt = null,
                    ),
                ),
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
                clock = object : Clock {
                    override fun now() = Instant.parse("2030-01-01T10:00:00Z").toKotlinInstant()
                },
                dispatchMetrics = {},
            )
            val metrics = MicrometerDialogDispatchMetrics({ noStats }, SimpleMeterRegistry())
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
                    configureDialogDispatch(DialogDispatchLoopSettings(interval = 5.seconds))
                    monitor.raise(ServerReady, environment)
                }
                startApplication()
                withTimeout(5.seconds) { entered.await() }
            }
            withTimeout(5.seconds) { cancelled.await() }
            queue.finishedResult.shouldBeNull()
        }
    })
