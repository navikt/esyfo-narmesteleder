package no.nav.syfo.application.task

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledLeaderTaskTest :
    DescribeSpec(
        {
            val logger = LoggerFactory.getLogger("TestScheduledLeaderTask") as Logger
            val appender = ListAppender<ILoggingEvent>()
            beforeSpec {
                appender.start()
                logger.addAppender(appender)
            }
            afterSpec {
                logger.detachAppender(appender)
                appender.stop()
            }
            beforeTest { appender.list.clear() }

            describe("ScheduledLeaderTask") {
                context("runTask") {
                    it("should call execute on each iteration") {
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

                            advanceTimeBy(100)
                            runCurrent()
                            executeCount.shouldBeExactly(2)

                            advanceTimeBy(100)
                            runCurrent()
                            executeCount.shouldBeExactly(3)

                            job.cancelAndJoin()
                        }
                    }

                    it("should continue running after exception in execute") {
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

                            advanceTimeBy(100)
                            runCurrent()
                            executeCount.shouldBeExactly(2)
                            val event = appender.list.single { it.level == Level.ERROR }
                            val fields = event.keyValuePairs.associate { it.key to it.value }
                            fields["event_type"] shouldBe "scheduled_task_failed"
                            fields["task_name"] shouldBe "TestScheduledLeaderTask"
                            fields.containsKey("operation") shouldBe false
                            fields.containsKey("error_code") shouldBe false
                            fields.containsKey("outcome") shouldBe false
                            fields["cause_type"] shouldBe "RuntimeException"
                            event.throwableProxy.message shouldBe "java.lang.RuntimeException"

                            job.cancelAndJoin()
                        }
                    }

                    it("should handle cancellation gracefully") {
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
                            appender.list.none { it.level == Level.ERROR } shouldBe true
                        }
                    }

                    it("does not log cancellation thrown by the task") {
                        runTest {
                            val task = TestScheduledLeaderTask(interval = 100.milliseconds) {
                                throw CancellationException("cancelled")
                            }
                            val job = launch { task.runTask() }
                            runCurrent()
                            job.isCompleted.shouldBeTrue()
                            appender.list.none { it.level == Level.ERROR } shouldBe true
                        }
                    }

                    it("logs wrapped cancellation as an ordinary task failure") {
                        runTest {
                            val task = TestScheduledLeaderTask(interval = 100.milliseconds) {
                                throw IllegalStateException("private-canary", CancellationException("cancelled"))
                            }
                            val job = launch { task.runTask() }
                            runCurrent()
                            job.isActive.shouldBeTrue()
                            appender.list.any { it.level == Level.ERROR } shouldBe true
                            job.cancelAndJoin()
                        }
                    }

                    it("should stop when cancelled") {
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
                            advanceTimeBy(1_000)
                            runCurrent()

                            executeCount.shouldBeExactly(1)
                        }
                    }
                }
            }
        },
    )

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
