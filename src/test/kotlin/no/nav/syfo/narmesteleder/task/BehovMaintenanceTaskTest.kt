package no.nav.syfo.narmesteleder.task

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
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
            checkForInactiveSykmeldingOnBehovsAfterDays = 7,
            maintenanceTaskDelay = "100ms",
            deleteDialogportenDialogsTaskProperties = mockk(relaxed = true),
            persistSendtSykmelding = mockk(relaxed = true),
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
                it("should call expireOldLinemanagerRequirements when leader") {
                    // Arrange
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { narmestelederService.expireOldLinemanagerRequirements(any()) } returns 5

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
                        narmestelederService.expireOldLinemanagerRequirements(
                            env.checkForInactiveSykmeldingOnBehovsAfterDays
                        )
                    }
                }

                it("should not call expireOldLinemanagerRequirements when not leader") {
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
                    coVerify(exactly = 0) { narmestelederService.expireOldLinemanagerRequirements(any()) }
                }

                it("should continue running after exception in expireOldLinemanagerRequirements") {
                    // Arrange
                    var callCount = 0
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { narmestelederService.expireOldLinemanagerRequirements(any()) } answers {
                        callCount++
                        if (callCount == 1) {
                            throw RuntimeException("Test exception")
                        }
                        3
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
                    coVerify(atLeast = 2) { narmestelederService.expireOldLinemanagerRequirements(any()) }
                }

                it("should use correct checkForInactiveSykmeldingOnBehovsAfterDays value") {
                    // Arrange
                    val customDays = 14L
                    val customEnv = env.copy(checkForInactiveSykmeldingOnBehovsAfterDays = customDays)
                    val task = BehovMaintenanceTask(
                        narmestelederService = narmestelederService,
                        leaderElection = leaderElection,
                        env = customEnv
                    )

                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { narmestelederService.expireOldLinemanagerRequirements(any()) } returns 0

                    // Act
                    val job = launch {
                        task.runTask()
                    }

                    delay(100.milliseconds)
                    job.cancelAndJoin()

                    // Assert
                    coVerify(atLeast = 1) {
                        narmestelederService.expireOldLinemanagerRequirements(eq(customDays))
                    }
                }

                it("should gracefully handle cancellation") {
                    // Arrange
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { narmestelederService.expireOldLinemanagerRequirements(any()) } returns 0

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
