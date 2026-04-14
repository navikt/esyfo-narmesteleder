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
import kotlin.time.Duration.Companion.milliseconds

class UpdateDialogTaskTest :
    DescribeSpec({
        val dialogportenService = mockk<DialogportenService>()
        val pollingInterval = 100.milliseconds

        lateinit var updateDialogTask: UpdateDialogTask

        beforeTest {
            clearAllMocks()
            updateDialogTask =
                UpdateDialogTask(
                    dialogportenService = dialogportenService,
                    pollingInterval = pollingInterval,
                )
        }

        describe("execute") {
            it("should call both setAllFulfilledBehovsAsCompletedInDialogporten and setAllExpiredBehovsAsExpiredAndCompletedInDialogporten") {
                coEvery { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() } just Runs
                coEvery { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() } just Runs

                val job = launch {
                    updateDialogTask.runTask()
                }

                delay(150.milliseconds)
                job.cancel()

                coVerify(atLeast = 1) { dialogportenService.setAllFulfilledBehovsAsCompletedInDialogporten() }
                coVerify(atLeast = 1) { dialogportenService.setAllExpiredBehovsAsExpiredAndCompletedInDialogporten() }
            }
        }
    })
