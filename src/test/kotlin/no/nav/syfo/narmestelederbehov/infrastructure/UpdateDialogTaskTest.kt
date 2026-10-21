package no.nav.syfo.narmestelederbehov.infrastructure

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nav.syfo.logging.ProductionLogs
import no.nav.syfo.narmestelederbehov.application.CompleteDialogsFixture
import no.nav.syfo.narmestelederbehov.application.ExpireDialogsFixture
import no.nav.syfo.narmestelederbehov.application.UPDATE_NOW
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateDialogTaskTest :
    FunSpec({
        val logs = extension(ProductionLogs(UpdateDialogTask::class.java, Level.WARN))

        test("executes completion then expiry") {
            runTest {
                val calls = mutableListOf<String>()
                val complete = CompleteDialogsFixture()
                val expire = ExpireDialogsFixture()
                complete.repository.findFulfilled = {
                    calls += "complete"
                    emptyList()
                }
                expire.repository.findExpired = {
                    calls += "expire"
                    emptyList()
                }
                val task = UpdateDialogTask(complete.useCase, expire.useCase, 5.minutes)

                task.execute()

                calls shouldBe listOf("complete", "expire")
                complete.repository.fulfilledQueries shouldBe listOf(UPDATE_NOW.minusSeconds(10))
                expire.repository.expiryQueries shouldBe 1
            }
        }

        test("cancellation during completion prevents expiry") {
            val complete = CompleteDialogsFixture()
            val expire = ExpireDialogsFixture()
            val cancellation = CancellationException("cancel test")
            complete.repository.findFulfilled = { throw cancellation }
            val task = UpdateDialogTask(complete.useCase, expire.useCase, 5.minutes)

            shouldThrow<CancellationException> { task.execute() } shouldBe cancellation

            expire.repository.expiryQueries shouldBe 0
        }

        test("scheduled failures use the new package name and retry only after the configured interval") {
            runTest {
                val complete = CompleteDialogsFixture()
                val expire = ExpireDialogsFixture()
                complete.repository.findFulfilled = { throw IllegalStateException("private-diagnostic-canary") }
                val task = UpdateDialogTask(complete.useCase, expire.useCase, 7.minutes)

                logs.capture().use { capture ->
                    val job = launch { task.runTask() }
                    try {
                        runCurrent()
                        complete.repository.fulfilledQueries.size shouldBe 1
                        advanceTimeBy(7.minutes.inWholeMilliseconds - 1)
                        runCurrent()
                        complete.repository.fulfilledQueries.size shouldBe 1
                        advanceTimeBy(1)
                        runCurrent()
                        complete.repository.fulfilledQueries.size shouldBe 2
                        expire.repository.expiryQueries shouldBe 0
                        capture.records.size shouldBe 2
                        capture.records.forEach { output ->
                            val record = jacksonObjectMapper().readTree(output)
                            record["event_type"].asText() shouldBe "scheduled_task_failed"
                            record["task_name"].asText() shouldBe "no.nav.syfo.narmestelederbehov.infrastructure.UpdateDialogTask"
                        }
                    } finally {
                        job.cancelAndJoin()
                    }
                }
            }
        }
    })
