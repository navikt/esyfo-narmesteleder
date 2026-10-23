package no.nav.syfo.platform.scheduling

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledLeaderTaskTest :
    FunSpec({
        val productionLogs = extension(ProductionLogs(listOf("TestScheduledLeaderTask")))
        lateinit var capture: ProductionLogCapture
        beforeTest { capture = productionLogs.capture() }
        afterTest { capture.close() }

        test("should call execute on each iteration") {
            runTest {
                var executeCount = 0
                val task = TestScheduledLeaderTask(
                    interval = 100.milliseconds,
                ) {
                    executeCount++
                }

                val job = launch {
                    task.runTask()
                }

                runCurrent()
                executeCount.shouldBeExactly(1)

                advanceTimeBy(100.milliseconds)
                runCurrent()
                executeCount.shouldBeExactly(2)

                advanceTimeBy(100.milliseconds)
                runCurrent()
                executeCount.shouldBeExactly(3)

                job.cancelAndJoin()
            }
        }

        test("should continue running after exception in execute") {
            runTest {
                var executeCount = 0
                val task = TestScheduledLeaderTask(
                    interval = 100.milliseconds,
                ) {
                    executeCount++
                    if (executeCount == 1) {
                        throw RuntimeException("Test exception")
                    }
                }

                val job = launch {
                    task.runTask()
                }

                runCurrent()
                executeCount.shouldBeExactly(1)

                advanceTimeBy(100.milliseconds)
                runCurrent()
                executeCount.shouldBeExactly(2)
                val event = capture.logs.single { it.level == Level.ERROR }
                val fields = event.fields
                fields["event_type"] shouldBe "scheduled_task_failed"
                fields["task_name"] shouldBe "TestScheduledLeaderTask"
                fields.containsKey("operation") shouldBe false
                fields.containsKey("error_code") shouldBe false
                fields.containsKey("outcome") shouldBe false
                fields["cause_type"] shouldBe "RuntimeException"
                event.throwableMessage shouldBe "java.lang.RuntimeException"
                event.serialized shouldNotContain "Test exception"

                job.cancelAndJoin()
            }
        }

        test("should handle cancellation gracefully") {
            runTest {
                val task = TestScheduledLeaderTask(
                    interval = 100.milliseconds,
                ) {}

                val job = launch {
                    task.runTask()
                }

                runCurrent()
                job.cancelAndJoin()

                job.isActive.shouldBeFalse()
                job.isCompleted.shouldBeTrue()
                capture.logs.none { it.level == Level.ERROR } shouldBe true
            }
        }

        test("does not log cancellation thrown by the task") {
            runTest {
                val task = TestScheduledLeaderTask(interval = 100.milliseconds) {
                    throw CancellationException("cancelled")
                }
                val job = launch { task.runTask() }
                runCurrent()
                job.isCompleted.shouldBeTrue()
                capture.logs.none { it.level == Level.ERROR } shouldBe true
            }
        }

        test("logs wrapped cancellation as an ordinary task failure") {
            runTest {
                val task = TestScheduledLeaderTask(interval = 100.milliseconds) {
                    throw IllegalStateException("private-canary", CancellationException("cancelled"))
                }
                val job = launch { task.runTask() }
                runCurrent()
                job.isActive.shouldBeTrue()
                capture.logs.any { it.level == Level.ERROR } shouldBe true
                job.cancelAndJoin()
            }
        }

        test("should stop when cancelled") {
            runTest {
                var executeCount = 0
                val task = TestScheduledLeaderTask(
                    interval = 100.milliseconds,
                ) {
                    executeCount++
                }

                val job = launch {
                    task.runTask()
                }

                runCurrent()
                executeCount.shouldBeExactly(1)

                job.cancelAndJoin()
                advanceTimeBy(1_000.milliseconds)
                runCurrent()

                executeCount.shouldBeExactly(1)
            }
        }
    })

private class TestScheduledLeaderTask(
    interval: Duration,
    private val onExecute: suspend () -> Unit,
) : ScheduledLeaderTask(
    name = "TestScheduledLeaderTask",
    interval = interval,
) {
    override suspend fun execute() {
        onExecute()
    }
}
