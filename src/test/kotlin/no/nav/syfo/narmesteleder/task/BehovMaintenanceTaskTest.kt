package no.nav.syfo.narmesteleder.task

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.environment.UpdateDialogportenTaskProperties
import no.nav.syfo.narmesteleder.service.NarmestelederService
import kotlin.time.Duration.Companion.milliseconds

class BehovMaintenanceTaskTest :
    DescribeSpec({
        val narmestelederService = mockk<NarmestelederService>()

        val env = OtherEnvironmentProperties(
            electorPath = "elector",
            electorSSEUrl = "not.applicable",
            frontendBaseUrl = "https://frontend.test.nav.no",
            publicIngressUrl = "https://test.nav.no",
            persistLeesahNlBehov = true,
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
            isDialogportenBackgroundTaskEnabled = true,
            daysAfterTomToExpireBehovs = 16,
            maintenanceTaskDelay = "100ms",
            persistSendtSykmelding = true,
            maintenanceTaskEnabled = true,
            persistNarmestelederRegister = false
        )

        fun createTask() = BehovMaintenanceTask(
            narmestelederService = narmestelederService,
            env = env,
        )

        beforeTest {
            clearAllMocks()
        }

        describe("BehovMaintenanceTask") {
            context("execute") {
                it("should call updateStatusOnExpiredBehovs") {
                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } just Runs

                    val task = createTask()

                    val job = launch {
                        task.runTask()
                    }

                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    coVerify(atLeast = 1) {
                        narmestelederService.updateStatusOnExpiredBehovs(
                            env.daysAfterTomToExpireBehovs,
                        )
                    }
                }

                it("should use correct daysAfterTomToExpireBehovs value") {
                    val customDays = 14L
                    val customEnv = env.copy(daysAfterTomToExpireBehovs = customDays)
                    val task = BehovMaintenanceTask(
                        narmestelederService = narmestelederService,
                        env = customEnv,
                    )

                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } just Runs

                    val job = launch {
                        task.runTask()
                    }

                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    coVerify(atLeast = 1) {
                        narmestelederService.updateStatusOnExpiredBehovs(eq(customDays))
                    }
                }
            }
        }
    })
