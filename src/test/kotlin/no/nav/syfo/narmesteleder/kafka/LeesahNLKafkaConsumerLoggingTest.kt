package no.nav.syfo.narmesteleder.kafka

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import kotlin.coroutines.EmptyCoroutineContext

class LeesahNLKafkaConsumerLoggingTest :
    StringSpec({
        "record processing failures retain partition and offset without payloads" {
            val logger = LoggerFactory.getLogger(LeesahNLKafkaConsumer::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>()
            appender.start()
            logger.addAppender(appender)
            try {
                val consumer = LeesahNLKafkaConsumer(
                    kafkaConsumer = mockk<KafkaConsumer<String, String>>(),
                    jacksonMapper = jacksonObjectMapper(),
                    handler = mockk(),
                    scope = CoroutineScope(EmptyCoroutineContext),
                )
                consumer.logConsumerFailure(LeesahRecordProcessingException(3, 42, IllegalStateException("private-canary")))

                val event = appender.list.single()
                val fields = event.keyValuePairs.associate { it.key to it.value }
                fields["event_type"] shouldBe "kafka_consumer_failed"
                fields["consumer"] shouldBe "NL_LEESAH"
                fields["partition"] shouldBe 3
                fields["offset"] shouldBe 42L
                event.formattedMessage.contains("private-canary") shouldBe false
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }

        "fatal errors are rethrown unchanged even when committing processing errors" {
            val consumer = LeesahNLKafkaConsumer(
                kafkaConsumer = mockk<KafkaConsumer<String, String>>(),
                jacksonMapper = jacksonObjectMapper(),
                handler = mockk(),
                scope = CoroutineScope(EmptyCoroutineContext),
            )
            consumer.commitOnAllErrors = true
            val fatal = LinkageError("synthetic fatal error")
            val record = ConsumerRecord(TEAMSYKMELDING_NL_LEESAH_TOPIC, 3, 42L, "key", "value")

            shouldThrow<LinkageError> { consumer.handleProccessingError(record, fatal) } shouldBe fatal
        }
    })
