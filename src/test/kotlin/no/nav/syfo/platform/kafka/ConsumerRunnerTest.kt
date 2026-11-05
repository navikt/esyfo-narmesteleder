package no.nav.syfo.platform.kafka

import ch.qos.logback.classic.Level
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.syfo.platform.observability.Heartbeat
import no.nav.syfo.platform.observability.withProductionLogs
import no.nav.syfo.sykmelding.application.SENDT_SYKMELDING_TOPIC
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterKafkaConsumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ConsumerRunnerTest :
    FunSpec({
        test("commits the highest next offset per partition after handling a batch") {
            val topicPartitions = listOf(TopicPartition(TOPIC, 0), TopicPartition(TOPIC, 1))
            val consumer = RecordingMockConsumer()
            val handled = CountDownLatch(1)
            var handledOffsets = emptyList<Long>()
            val runner = runner(
                consumerFactory = { consumer },
                handler = { records ->
                    handledOffsets = records.map { it.offset() }
                    handled.countDown()
                },
            )
            consumer.scheduleBatch(
                ConsumerRecord(TOPIC, 0, 2L, "key", "value"),
                ConsumerRecord(TOPIC, 0, 4L, "key", "value"),
                ConsumerRecord(TOPIC, 1, 7L, "key", "value"),
            )

            runner.start()
            handled.awaitOrFail()
            runner.close()

            assertSoftly {
                handledOffsets.shouldContainExactly(2L, 4L, 7L)
                consumer.committedOffsets.mapValues { it.value.offset() } shouldBe mapOf(
                    topicPartitions[0] to 5L,
                    topicPartitions[1] to 8L,
                )
                consumer.commitCount shouldBeExactly 1L
                consumer.closeCount shouldBeExactly 1L
            }
        }

        test("does not commit a failed batch and redelivers it after restarting") {
            val partition = TopicPartition(TOPIC, 0)
            val consumers = mutableListOf<RecordingMockConsumer>()
            val attempts = AtomicInteger()
            val handledTwice = CountDownLatch(2)
            val runner = runner(
                consumerFactory = {
                    RecordingMockConsumer().also { consumer ->
                        consumers += consumer
                        consumer.scheduleBatch(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
                    }
                },
                handler = {
                    handledTwice.countDown()
                    if (attempts.getAndIncrement() == 0) error("temporary handler failure")
                },
                initialBackoff = 1.milliseconds,
                maxBackoff = 1.milliseconds,
            )

            runner.start()
            handledTwice.awaitOrFail()
            runner.close()

            assertSoftly {
                consumers.size shouldBe 2
                consumers[0].committedOffsets shouldBe emptyMap()
                consumers[1].committedOffsets[partition]?.offset() shouldBe 1L
            }
        }

        test("restarts after a temporary poll failure and records the restart") {
            val partition = TopicPartition(TOPIC, 0)
            val consumers = mutableListOf<RecordingMockConsumer>()
            val handled = CountDownLatch(1)
            val registry = SimpleMeterRegistry()
            val runner = runner(
                consumerFactory = {
                    if (consumers.isEmpty()) {
                        FailingPollConsumer().also(consumers::add)
                    } else {
                        RecordingMockConsumer().also { consumer ->
                            consumers += consumer
                            consumer.scheduleBatch(ConsumerRecord(TOPIC, 0, 0L, "key", "value"))
                        }
                    }
                },
                handler = { handled.countDown() },
                registry = registry,
                initialBackoff = 1.milliseconds,
                maxBackoff = 1.milliseconds,
            )

            runner.start()
            handled.awaitOrFail()
            runner.close()

            assertSoftly {
                consumers.size shouldBe 2
                consumers.all { it.closed() } shouldBe true
                registry.counter(ConsumerRunner.RESTARTS_METRIC, "consumer", CONSUMER_NAME).count() shouldBe 1.0
            }
        }

        test("join times out while running and close joins the stopped loop") {
            val consumer = RecordingMockConsumer()
            val runner = runner(consumerFactory = { consumer }, handler = {})
            runner.start()
            try {
                runner.join(10.milliseconds) shouldBe false
            } finally {
                runner.close()
            }
            assertSoftly {
                runner.join(1.seconds) shouldBe true
                consumer.closed() shouldBe true
            }
        }

        test("factory failures double backoff to the cap and a healthy lifecycle resets it") {
            val attempts = AtomicInteger()
            val polling = CountDownLatch(1)
            val consumer = RecordingMockConsumer()
            consumer.schedulePollTask { polling.countDown() }
            val runner = ConsumerRunner(
                consumerFactory = {
                    when (attempts.getAndIncrement()) {
                        0, 1, 2, 3 -> error("temporary factory failure")
                        4 -> {
                            Thread.sleep(1_100)
                            error("failure after a healthy lifecycle")
                        }
                        else -> consumer
                    }
                },
                topics = listOf(TOPIC),
                handler = {},
                initialBackoff = 1.milliseconds,
                maxBackoff = 4.milliseconds,
                healthyResetThreshold = 1.seconds,
                meterRegistry = SimpleMeterRegistry(),
            )
            withProductionLogs(ConsumerRunner::class.java, Level.WARN) { logs ->
                runner.start()
                try {
                    polling.awaitOrFail()
                } finally {
                    runner.close()
                }
                logs.logs.filter { it.fields["event_type"] == "kafka_consumer.restart" }
                    .map { (it.fields["backoff_millis"] as Number).toLong() }
                    .shouldContainExactly(1L, 2L, 4L, 4L, 1L)
            }
        }

        test("wrapped configuration failure from the factory stops without retry") {
            val attempts = AtomicInteger()
            val failure = KafkaException("wrapper-canary", ConfigException("configuration-canary"))
            var reported: Throwable? = null
            val runner = ConsumerRunner<String, String?>(
                consumerFactory = {
                    attempts.incrementAndGet()
                    throw failure
                },
                topics = listOf(TOPIC),
                handler = {},
                meterRegistry = SimpleMeterRegistry(),
            )
            runner.start { reported = it }
            try {
                runner.join(5.seconds) shouldBe true
                assertSoftly {
                    attempts.get() shouldBe 1
                    reported shouldBe failure
                }
            } finally {
                runner.close()
            }
        }

        test("empty polls refresh the internal heartbeat") {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val heartbeat = Heartbeat(clock, 10.milliseconds)
            val consumer = RecordingMockConsumer()
            val polled = CountDownLatch(1)
            consumer.schedulePollTask { clock.current += 20.milliseconds }
            consumer.schedulePollTask { polled.countDown() }
            val runner = ConsumerRunner(
                consumerFactory = { consumer },
                topics = listOf(TOPIC),
                handler = {},
                heartbeat = heartbeat,
                meterRegistry = SimpleMeterRegistry(),
            )
            runner.start()
            try {
                polled.awaitOrFail()
                runner.isAlive() shouldBe true
            } finally {
                runner.close()
            }
            clock.current += 20.milliseconds
            runner.isAlive() shouldBe false
        }

        test("stops and logs fatal authentication, authorization, and configuration failures") {
            listOf(
                AuthenticationException("auth-canary"),
                AuthorizationException("authorization-canary"),
                KafkaException("wrapper-canary", ConfigException("configuration-canary")),
            ).forEach { failure ->
                val fatalReported = CountDownLatch(1)
                val registry = SimpleMeterRegistry()
                val consumer = FatalPollConsumer(failure)
                val runner = runner(
                    consumerFactory = { consumer },
                    handler = {},
                    registry = registry,
                    initialBackoff = 1.milliseconds,
                    maxBackoff = 1.milliseconds,
                )

                withProductionLogs(ConsumerRunner::class.java, Level.ERROR) { logs ->
                    runner.start { fatalReported.countDown() }
                    fatalReported.awaitOrFail()
                    runner.close()

                    assertSoftly {
                        consumer.closed() shouldBe true
                        registry.counter(ConsumerRunner.RESTARTS_METRIC, "consumer", CONSUMER_NAME).count() shouldBe 0.0
                        logs.logs.any {
                            it.fields["event_type"] == "kafka_consumer.fatal_failure" && it.fields["consumer"] == CONSUMER_NAME
                        } shouldBe true
                        logs.logs.none { log ->
                            listOf("auth-canary", "authorization-canary", "configuration-canary")
                                .any(log.serialized::contains)
                        } shouldBe true
                    }
                }
            }
        }

        test("stops cleanly and closes the active consumer once") {
            val polling = CountDownLatch(1)
            val consumer = RecordingMockConsumer()
            val runner = runner(
                consumerFactory = { consumer.also { it.schedulePollTask { polling.countDown() } } },
                handler = {},
            )

            runner.start()
            polling.awaitOrFail()
            runner.close()

            assertSoftly {
                consumer.closed() shouldBe true
                consumer.closeCount shouldBeExactly 1L
            }
        }

        test("does not log message payloads included in handler exceptions") {
            val payload = "payload-canary"
            val handled = CountDownLatch(1)
            val attempts = AtomicInteger()
            val runner = runner(
                consumerFactory = {
                    RecordingMockConsumer().also { consumer ->
                        consumer.scheduleBatch(ConsumerRecord(TOPIC, 0, 0L, "key", payload))
                    }
                },
                handler = {
                    if (attempts.getAndIncrement() == 0) throw IllegalStateException(payload)
                    handled.countDown()
                },
                initialBackoff = 1.milliseconds,
                maxBackoff = 1.milliseconds,
            )

            withProductionLogs(ConsumerRunner::class.java, Level.WARN) { logs ->
                runner.start()
                handled.awaitOrFail()
                runner.close()

                assertSoftly {
                    logs.logs.any { it.fields["event_type"] == "kafka_consumer.restart" } shouldBe true
                    logs.logs.none { it.serialized.contains(payload) } shouldBe true
                }
            }
        }

        test("the sykmeldingsregister consumer uses earliest offsets and processes without logging the payload") {
            val partition = TopicPartition(SENDT_SYKMELDING_TOPIC, 0)
            val payload = "payload-canary"
            val committed = CountDownLatch(1)
            val consumer = RecordingMockConsumer(committed)
            val registry = SimpleMeterRegistry()
            val sykmeldingsregisterConsumer = SykmeldingsregisterKafkaConsumer(
                consumerFactory = { consumer },
                meterRegistry = registry,
            )
            consumer.scheduleBatch(ConsumerRecord(SENDT_SYKMELDING_TOPIC, 0, 0L, "key", payload))

            val properties = SykmeldingsregisterKafkaConsumer.kafkaConsumerProperties(KafkaEnvironment.createForLocal())
            assertSoftly {
                properties.getProperty("group.id") shouldBe "esyfo-narmesteleder-sykmeldingsregister-consumer"
                properties.getProperty("auto.offset.reset") shouldBe "earliest"
                properties.getProperty("enable.auto.commit") shouldBe "false"
            }

            withProductionLogs(ConsumerRunner::class.java, Level.INFO) { logs ->
                sykmeldingsregisterConsumer.listen()
                committed.awaitOrFail()
                sykmeldingsregisterConsumer.stop()

                logs.logs.none { it.serialized.contains(payload) } shouldBe true
            }

            assertSoftly {
                registry.counter(ConsumerRunner.PROCESSED_MESSAGES_METRIC, "consumer", "sykmeldingsregister").count() shouldBe 1.0
                consumer.committedOffsets[partition]?.offset() shouldBe 1L
            }
        }
    })

private const val TOPIC = "test-topic"
private const val CONSUMER_NAME = "sykmeldingsregister"

private class MutableClock(var current: Instant) : Clock {
    override fun now(): Instant = current
}

private fun RecordingMockConsumer.scheduleBatch(vararg records: ConsumerRecord<String, String?>) {
    val partitions = records.map { TopicPartition(it.topic(), it.partition()) }.distinct()
    schedulePollTask {
        rebalance(partitions)
        updateBeginningOffsets(partitions.associateWith { 0L })
        records.forEach(::addRecord)
    }
}

private fun runner(
    consumerFactory: () -> RecordingMockConsumer,
    handler: BatchMessageHandler<String, String?>,
    registry: SimpleMeterRegistry = SimpleMeterRegistry(),
    initialBackoff: kotlin.time.Duration = 10.milliseconds,
    maxBackoff: kotlin.time.Duration = 100.milliseconds,
) = ConsumerRunner(
    consumerFactory = consumerFactory,
    topics = listOf(TOPIC),
    handler = handler,
    coroutineName = CONSUMER_NAME,
    initialBackoff = initialBackoff,
    maxBackoff = maxBackoff,
    meterRegistry = registry,
)

private fun CountDownLatch.awaitOrFail() {
    await(5, TimeUnit.SECONDS) shouldBe true
}

private open class RecordingMockConsumer(
    private val committed: CountDownLatch? = null,
) : MockConsumer<String, String?>("earliest") {
    val committedOffsets = mutableMapOf<TopicPartition, OffsetAndMetadata>()
    var commitCount = 0L
        private set
    var closeCount = 0L
        private set

    override fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>) {
        commitCount++
        committedOffsets.putAll(offsets)
        super.commitSync(offsets)
        committed?.countDown()
    }

    override fun close() {
        closeCount++
        super.close()
    }
}

private class FailingPollConsumer : RecordingMockConsumer() {
    private val failed = AtomicInteger()

    override fun poll(timeout: Duration?): ConsumerRecords<String, String?> {
        if (failed.getAndIncrement() == 0) error("temporary poll failure")
        return super.poll(timeout)
    }
}

private class FatalPollConsumer(
    private val failure: Throwable,
) : RecordingMockConsumer() {
    override fun poll(timeout: Duration?): ConsumerRecords<String, String?> = throw failure
}
