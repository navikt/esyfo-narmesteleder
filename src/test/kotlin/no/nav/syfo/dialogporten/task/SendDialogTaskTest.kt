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

        beforeTest {
            clearAllMocks()
        }

        describe("execute") {
            it("should call sendDocumentsToDialogporten") {
                coEvery { dialogportenService.sendDocumentsToDialogporten() } just Runs

                val task = SendDialogTask(dialogportenService = dialogportenService)

                val job = launch {
                    task.runTask()
                }

                delay(100.milliseconds)
                job.cancel()

                coVerify(atLeast = 1) { dialogportenService.sendDocumentsToDialogporten() }
            }
        }
    })
