package no.nav.syfo.narmesteleder.service

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import no.nav.syfo.application.kafka.KafkaListener

class LeaderControlledKafkaConsumerTest :
    DescribeSpec({
        describe("onLeaderChange") {
            it("should only start the consumer when leadership is acquired") {
                val consumer = TestKafkaListener()
                val leaderControlledConsumer = LeaderControlledKafkaConsumer(
                    consumer = consumer,
                )

                runTest {
                    leaderControlledConsumer.onLeaderChange(false)
                    leaderControlledConsumer.onLeaderChange(true)
                }

                consumer.listenCount shouldBe 1
                consumer.stopCount shouldBe 0
            }

            it("should stop the consumer when leadership is lost") {
                val consumer = TestKafkaListener()
                val leaderControlledConsumer = LeaderControlledKafkaConsumer(
                    consumer = consumer,
                )

                runTest {
                    leaderControlledConsumer.onLeaderChange(true)
                    leaderControlledConsumer.onLeaderChange(false)
                }

                consumer.listenCount shouldBe 1
                consumer.stopCount shouldBe 1
            }

            it("should not start the consumer twice for repeated leader events") {
                val consumer = TestKafkaListener()
                val leaderControlledConsumer = LeaderControlledKafkaConsumer(
                    consumer = consumer,
                )

                runTest {
                    leaderControlledConsumer.onLeaderChange(true)
                    leaderControlledConsumer.onLeaderChange(true)
                    leaderControlledConsumer.onLeaderChange(false)
                    leaderControlledConsumer.onLeaderChange(false)
                }

                consumer.listenCount shouldBe 1
                consumer.stopCount shouldBe 1
            }

            it("should wait for stop to finish before starting again") {
                val stopStarted = CompletableDeferred<Unit>()
                val allowStopToFinish = CompletableDeferred<Unit>()
                val consumer = BlockingTestKafkaListener(
                    stopStarted = stopStarted,
                    allowStopToFinish = allowStopToFinish,
                )
                val leaderControlledConsumer = LeaderControlledKafkaConsumer(
                    consumer = consumer,
                )

                runTest {
                    leaderControlledConsumer.onLeaderChange(true)

                    val stopJob = launch {
                        leaderControlledConsumer.onLeaderChange(false)
                    }
                    stopStarted.await()

                    val restartJob = launch {
                        leaderControlledConsumer.onLeaderChange(true)
                    }

                    consumer.listenCount shouldBe 1
                    consumer.stopCount shouldBe 1

                    allowStopToFinish.complete(Unit)

                    stopJob.join()
                    restartJob.join()
                }

                consumer.listenCount shouldBe 2
                consumer.stopCount shouldBe 1
            }
        }
    })

private class TestKafkaListener : KafkaListener {
    var listenCount = 0
    var stopCount = 0

    override fun listen() {
        listenCount += 1
    }

    override suspend fun stop() {
        stopCount += 1
    }
}

private class BlockingTestKafkaListener(
    private val stopStarted: CompletableDeferred<Unit>,
    private val allowStopToFinish: CompletableDeferred<Unit>,
) : KafkaListener {
    var listenCount = 0
    var stopCount = 0

    override fun listen() {
        listenCount += 1
    }

    override suspend fun stop() {
        stopCount += 1
        stopStarted.complete(Unit)
        allowStopToFinish.await()
    }
}
