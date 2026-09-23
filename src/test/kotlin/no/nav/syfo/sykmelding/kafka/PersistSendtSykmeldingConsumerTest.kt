package no.nav.syfo.sykmelding.kafka

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.kafka.jacksonMapper
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

class PersistSendtSykmeldingConsumerTest :
    DescribeSpec({
        val kafkaConsumer = mockk<KafkaConsumer<String, String?>>(relaxed = true)
        val handler = mockk<SendtSykmeldingHandler>(relaxed = true)
        val consumer = PersistSendtSykmeldingConsumer(
            handler = handler,
            jacksonMapper = jacksonMapper(),
            kafkaConsumer = kafkaConsumer,
            scope = kotlinx.coroutines.CoroutineScope(EmptyCoroutineContext),
            env = OtherEnvironmentProperties.createForLocal(),
        )

        beforeTest {
            clearMocks(kafkaConsumer, handler)
        }

        describe("stop") {
            it("should wake up, unsubscribe and close the consumer before returning") {
                val subscribeStarted = CompletableDeferred<Unit>()
                val pollReleased = CompletableDeferred<Unit>()

                every { kafkaConsumer.subscribe(any<List<String>>()) } answers {
                    subscribeStarted.complete(Unit)
                }

                every { kafkaConsumer.poll(any()) } answers {
                    runBlocking {
                        pollReleased.await()
                    }
                    throw WakeupException()
                }
                every { kafkaConsumer.wakeup() } answers {
                    pollReleased.complete(Unit)
                }
                every { kafkaConsumer.unsubscribe() } just Runs
                every { kafkaConsumer.close(any<CloseOptions>()) } returns Unit

                runTest {
                    consumer.listen()
                    subscribeStarted.await()
                    consumer.stop()
                }

                verify(exactly = 1) { kafkaConsumer.wakeup() }
                verify(exactly = 1) { kafkaConsumer.unsubscribe() }
                verify(exactly = 1) { kafkaConsumer.close(any<CloseOptions>()) }
            }
        }

        it("logs a fatal batch Error once, then rethrows it without committing") {
            val fatal = LinkageError("private-canary")
            val failure = CompletableDeferred<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, thrown -> failure.complete(thrown) })
            val batchConsumer = PersistSendtSykmeldingConsumer(
                handler = handler,
                jacksonMapper = jacksonMapper(),
                kafkaConsumer = kafkaConsumer,
                scope = scope,
                env = OtherEnvironmentProperties.createForLocal(),
            )
            batchConsumer.commitOnAllErrors = true
            val record = ConsumerRecord<String, String?>(
                "teamsykmelding.syfo-sendt-sykmelding",
                0,
                42L,
                UUID.randomUUID().toString(),
                null,
            )
            every { kafkaConsumer.poll(any()) } returns ConsumerRecords(
                mapOf(TopicPartition(record.topic(), record.partition()) to listOf(record)),
                emptyMap(),
            )
            coEvery { handler.handleSykmeldingBatch(any()) } throws fatal
            val logger = LoggerFactory.getLogger(PersistSendtSykmeldingConsumer::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                batchConsumer.listen()
                withTimeout(5_000) { failure.await() } shouldBe fatal
                val event = appender.list.single()
                event.level shouldBe Level.ERROR
                val fields = event.keyValuePairs.associate { it.key to it.value }
                fields["event_type"] shouldBe "kafka_consumer_crashed"
                fields["consumer"] shouldBe "SENT_SYKMELDING_PERSIST"
                event.formattedMessage.contains("private-canary") shouldBe false
                verify(exactly = 0) { kafkaConsumer.commitSync() }
            } finally {
                logger.detachAppender(appender)
                appender.stop()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    })
