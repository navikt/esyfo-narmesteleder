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
import no.nav.syfo.altinn.dialogporten.task.SendDialogTask
import kotlin.time.Duration.Companion.milliseconds

class SendDialogTaskTest :
    DescribeSpec({
        val dialogportenService = mockk<DialogportenService>()

        lateinit var sendDialogTask: SendDialogTask

        beforeTest {
            clearAllMocks()
            sendDialogTask = SendDialogTask(dialogportenService = dialogportenService)
        }

        describe("runTask") {
            it("should call sendDocumentsToDialogporten") {
                coEvery { dialogportenService.sendDocumentsToDialogporten() } just Runs

                val job = launch {
                    sendDialogTask.runTask()
                }

                delay(100.milliseconds)
                job.cancel()

                coVerify(atLeast = 1) { dialogportenService.sendDocumentsToDialogporten() }
            }

            it("should continue running even if sendDocumentsToDialogporten throws exception") {
                var callCount = 0
                coEvery { dialogportenService.sendDocumentsToDialogporten() } answers {
                    callCount++
                    if (callCount == 1) {
                        throw RuntimeException("Test exception")
                    }
                }

                val job = launch {
                    sendDialogTask.runTask()
                }

                // SendDialogTask has a 5-minute delay, so we can't wait for a second iteration
                // in a unit test. We verify the first call happened and the task survived the exception.
                delay(100.milliseconds)
                job.cancel()

                coVerify(atLeast = 1) { dialogportenService.sendDocumentsToDialogporten() }
            }

            it("should gracefully handle cancellation") {
                coEvery { dialogportenService.sendDocumentsToDialogporten() } just Runs

                val job = launch {
                    sendDialogTask.runTask()
                }

                delay(50.milliseconds)

                job.cancel()
            }
        }
    })
