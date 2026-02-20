package no.nav.syfo.dialogporten.task

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.dialogporten.task.UpdateDialogTask
import no.nav.syfo.application.leaderelection.LeaderElection
import kotlin.time.Duration.Companion.milliseconds

class UpdateDialogTaskTest :
    DescribeSpec({
        val dialogportenService = mockk<DialogportenService>()
        val leaderElection = mockk<LeaderElection>()
        val pollingInterval = 100.milliseconds

        lateinit var updateDialogTask: UpdateDialogTask

        beforeTest {
            clearAllMocks()
            updateDialogTask =
                UpdateDialogTask(
                    leaderElection = leaderElection,
                    dialogportenService = dialogportenService,
                    pollingInterval = pollingInterval
                )
        }

        describe("runTask") {
            context("when pod is leader") {
                it("should call both setAllFulfilledBehovsAsCompletedInDialogporten and setAllExpiredBehovsAsExpiredAndCompletedInDialogporten") {
                    // Arrange
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() } just Runs
                    coEvery { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() } just Runs

                    // Act
                    val job = launch {
                        updateDialogTask.runTask()
                    }

                    // Give it time to execute at least once
                    delay(150.milliseconds)
                    job.cancel()

                    // Assert
                    coVerify(atLeast = 1) { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() }
                    coVerify(atLeast = 1) { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() }
                }

                it("should continue running even if setAllFulfilledBehovsAsCompletedInDialogporten throws exception") {
                    // Arrange
                    var callCount = 0
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() } answers {
                        callCount++
                        if (callCount == 1) {
                            throw RuntimeException("Test exception")
                        }
                    }
                    coEvery { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() } just Runs

                    // Act
                    val job = launch {
                        updateDialogTask.runTask()
                    }

                    // Give it time to execute multiple times
                    delay(250.milliseconds)
                    job.cancel()

                    // Assert - should have been called at least twice (once failed, once succeeded)
                    coVerify(atLeast = 2) { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() }
                    coVerify(atLeast = 1) { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() }
                }

                it("should continue running even if setAllExpiredBehovsAsExpiredAndCompletedInDialogporten throws exception") {
                    // Arrange
                    var callCount = 0
                    coEvery { leaderElection.isLeader() } returns true
                    coEvery { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() } just Runs
                    coEvery { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() } answers {
                        callCount++
                        if (callCount == 1) {
                            throw RuntimeException("Test exception")
                        }
                    }

                    // Act
                    val job = launch {
                        updateDialogTask.runTask()
                    }

                    // Give it time to execute multiple times
                    delay(250.milliseconds)
                    job.cancel()

                    // Assert - should have been called at least twice (once failed, once succeeded)
                    coVerify(atLeast = 1) { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() }
                    coVerify(atLeast = 2) { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() }
                }
            }

            context("when pod is not leader") {
                it("should not call dialogporten service methods") {
                    // Arrange
                    coEvery { leaderElection.isLeader() } returns false

                    // Act
                    val job = launch {
                        updateDialogTask.runTask()
                    }

                    // Give it time to execute at least once
                    delay(150.milliseconds)
                    job.cancel()

                    // Assert
                    coVerify(exactly = 0) { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() }
                    coVerify(exactly = 0) { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() }
                }
            }

            context("when leadership changes") {
                it("should start calling service methods when becoming leader") {
                    // Arrange
                    var isLeader = false
                    coEvery { leaderElection.isLeader() } answers { isLeader }
                    coEvery { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() } just Runs
                    coEvery { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() } just Runs

                    // Act
                    val job = launch {
                        updateDialogTask.runTask()
                    }

                    // Wait while not leader
                    delay(150.milliseconds)

                    // Verify no calls while not leader
                    coVerify(exactly = 0) { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() }
                    coVerify(exactly = 0) { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() }

                    // Become leader
                    isLeader = true
                    delay(150.milliseconds)
                    job.cancel()

                    // Assert - should have been called after becoming leader
                    coVerify(atLeast = 1) { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() }
                    coVerify(atLeast = 1) { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() }
                }
            }
        }
    })
