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
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.narmesteleder.service.NarmestelederService
import kotlin.time.Duration.Companion.milliseconds

class BehovMaintenanceTaskTest :
    DescribeSpec({
        val narmestelederService = mockk<NarmestelederService>()
        val leaderElection = mockk<LeaderElection>()

        val env = OtherEnvironmentProperties(
            electorPath = "elector",
            frontendBaseUrl = "https://frontend.test.nav.no",
            publicIngressUrl = "https://test.nav.no",
            persistLeesahNlBehov = true,
            updateDialogportenTaskProperties = UpdateDialogportenTaskProperties.createForLocal(),
            isDialogportenBackgroundTaskEnabled = true,
            dialogportenIsApiOnly = false,
            daysAfterTomToExpireBehovs = 16,
            maintenanceTaskDelay = "100ms",
            deleteDialogportenDialogsTaskProperties = mockk(relaxed = true),
            persistSendtSykmelding = mockk(relaxed = true),
            maintenanceTaskEnabled = true,
        )

        fun createTask() = BehovMaintenanceTask(
            narmestelederService = narmestelederService,
            leaderElection = leaderElection,
            env = env
        )

        beforeTest {
            clearAllMocks()
        }

        describe("BehovMaintenanceTask") {
            context("runTask") {
                it("should call updateStatusOnExpiredBehovs when leader") {
                    // Arrange
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } just Runs

                    val task = createTask()

                    // Act
                    val job = launch {
                        task.runTask()
                    }

                    // Wait for task to run at least once
                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    // Assert
                    coVerify(atLeast = 1) { leaderElection.isLeader() }
                    coVerify(atLeast = 1) {
                        narmestelederService.updateStatusOnExpiredBehovs(
                            env.daysAfterTomToExpireBehovs
                        )
                    }
                }

                it("should not call updateStatusOnExpiredBehovs when not leader") {
                    // Arrange
                    coEvery { leaderElection.isLeader() } returns false

                    val task = createTask()

                    // Act
                    val job = launch {
                        task.runTask()
                    }

                    // Wait for task to run at least once
                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    // Assert
                    coVerify(atLeast = 1) { leaderElection.isLeader() }
                    coVerify(exactly = 0) { narmestelederService.updateStatusOnExpiredBehovs(any()) }
                }

                it("should continue running after exception in updateStatusOnExpiredBehovs") {
                    // Arrange
                    var callCount = 0
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } answers {
                        callCount++
                        if (callCount == 1) {
                            throw RuntimeException("Test exception")
                        }
                    }

                    val task = createTask()

                    // Act
                    val job = launch {
                        task.runTask()
                    }

                    // Wait for task to run multiple times
                    delay(150.milliseconds)
                    job.cancelAndJoin()

                    // Assert - should have been called at least twice (once with exception, once without)
                    coVerify(atLeast = 2) { narmestelederService.updateStatusOnExpiredBehovs(any()) }
                }

                it("should use correct daysAfterTomToExpireBehovs value") {
                    // Arrange
                    val customDays = 14L
                    val customEnv = env.copy(daysAfterTomToExpireBehovs = customDays)
                    val task = BehovMaintenanceTask(
                        narmestelederService = narmestelederService,
                        leaderElection = leaderElection,
                        env = customEnv
                    )

                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } just Runs

                    // Act
                    val job = launch {
                        task.runTask()
                    }

                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    // Assert
                    coVerify(atLeast = 1) {
                        narmestelederService.updateStatusOnExpiredBehovs(eq(customDays))
                    }
                }

                it("should gracefully handle cancellation") {
                    // Arrange
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { narmestelederService.updateStatusOnExpiredBehovs(any()) } just Runs

                    val task = createTask()

                    // Act
                    val job = launch {
                        task.runTask()
                    }

                    delay(50.milliseconds)

                    // Assert - should not throw when cancelled
                    job.cancelAndJoin()
                }
            }
        }
    })
