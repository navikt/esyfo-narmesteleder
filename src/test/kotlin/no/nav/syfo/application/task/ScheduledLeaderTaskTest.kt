package no.nav.syfo.application.task

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledLeaderTaskTest :
    DescribeSpec(
        {
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
