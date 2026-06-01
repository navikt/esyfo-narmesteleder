package no.nav.syfo.pdl.leesah

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import no.nav.person.pdl.leesah.Endringstype
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.person.pdl.leesah.navn.Navn
import no.nav.person.pdl.leesah.navn.OriginaltNavn
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.metric.METRICS_REGISTRY
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RecordDeserializationException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.EmptyCoroutineContext

class PdlLeesahConsumerTest :
    DescribeSpec({
        val kafkaConsumer = mockk<KafkaConsumer<String, Personhendelse>>(relaxed = true)
        val testScope = CoroutineScope(EmptyCoroutineContext)
        val logAppender = ListAppender<ILoggingEvent>()
        val logger = LoggerFactory.getLogger("no.nav.syfo.pdl.leesah.PdlLeesahConsumer") as Logger

        beforeSpec {
            logger.level = Level.INFO
            logAppender.start()
            logger.addAppender(logAppender)
        }

        afterSpec {
            logger.detachAppender(logAppender)
        }

        beforeTest {
            clearMocks(kafkaConsumer)
            logAppender.list.clear()
            removePdlLeesahMetrics()
        }

        describe("processRecords") {
            it("ignores non-name events and commits offsets") {
                val consumer = createConsumer(kafkaConsumer = kafkaConsumer)
                val records = consumerRecords(
                    consumerRecord(
                        offset = 1L,
                        value = personhendelse(
                            opplysningstype = "DOEDSFALL_V1",
                            navn = null,
                        ),
                    ),
                )

                consumer.processRecords(records, kafkaConsumer)

                verify(exactly = 1) {
                    kafkaConsumer.commitSync(
                        mapOf(TopicPartition(PdlLeesahConsumer.PDL_LEESAH_TOPIC, 0) to OffsetAndMetadata(2))
                    )
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_IGNORED,
                    opplysningstype = "DOEDSFALL_V1",
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 1.0
            }

            it("processes relevant name events, logs only structural fields and commits offsets") {
                val consumer = createConsumer(kafkaConsumer = kafkaConsumer)
                val fnr = "12345678910"
                val fornavn = "Ola"
                val etternavn = "Nordmann"
                val records = consumerRecords(
                    consumerRecord(
                        offset = 2L,
                        key = fnr,
                        value = personhendelse(
                            personidenter = listOf(fnr),
                            navn = navn(
                                fornavn = fornavn,
                                mellomnavn = "Mellom",
                                etternavn = etternavn,
                                originaltNavn = OriginaltNavn("Zyxel", null, "Navneverdi"),
                            ),
                        ),
                    ),
                )

                consumer.processRecords(records, kafkaConsumer)

                verify(exactly = 1) {
                    kafkaConsumer.commitSync(
                        mapOf(TopicPartition(PdlLeesahConsumer.PDL_LEESAH_TOPIC, 0) to OffsetAndMetadata(3))
                    )
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_PROCESSED,
                    opplysningstype = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 1.0

                val logMessage = logAppender.list.joinToString("\n") { it.formattedMessage }
                logMessage.contains("opplysningstype=${PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE}") shouldBe true
                logMessage.contains("endringstype=${Endringstype.KORRIGERT}") shouldBe true
                logMessage.contains("hasFornavn=true") shouldBe true
                logMessage.contains("hasMellomnavn=true") shouldBe true
                logMessage.contains("hasEtternavn=true") shouldBe true
                logMessage.contains("hasOriginaltNavn=true") shouldBe true
                logMessage.contains(fnr) shouldBe false
                logMessage.contains(fornavn) shouldBe false
                logMessage.contains(etternavn) shouldBe false
                logAppender.list.joinToString("\n") { it.formattedMessage }.contains("Zyxel") shouldBe false
            }

            it("handles tombstone records explicitly and commits offsets") {
                val consumer = createConsumer(kafkaConsumer = kafkaConsumer)
                val records = consumerRecords(
                    consumerRecord(
                        offset = 3L,
                        value = null,
                    ),
                )

                consumer.processRecords(records, kafkaConsumer)

                verify(exactly = 1) {
                    kafkaConsumer.commitSync(
                        mapOf(TopicPartition(PdlLeesahConsumer.PDL_LEESAH_TOPIC, 0) to OffsetAndMetadata(4))
                    )
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_TOMBSTONE,
                    opplysningstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                    endringstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                ) shouldBeExactly 1.0
            }
        }

        describe("pollAndProcess") {
            it("skips poison-pill records deterministically without logging payload details") {
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    retryDelaySeconds = 0,
                )
                val topicPartition = TopicPartition(PdlLeesahConsumer.PDL_LEESAH_TOPIC, 0)
                every { kafkaConsumer.poll(any<Duration>()) } throws RecordDeserializationException(
                    RecordDeserializationException.DeserializationExceptionOrigin.VALUE,
                    topicPartition,
                    42L,
                    0L,
                    TimestampType.NO_TIMESTAMP_TYPE,
                    null,
                    null,
                    RecordHeaders(),
                    "failed to deserialize payload for 12345678910",
                    SerializationException("payload contains Ola Nordmann")
                )

                runTest {
                    consumer.pollAndProcess(kafkaConsumer)
                }

                verify(exactly = 1) { kafkaConsumer.seek(topicPartition, 43L) }
                verify(exactly = 1) {
                    kafkaConsumer.commitSync(mapOf(topicPartition to OffsetAndMetadata(43L)))
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_RECORD_DESERIALIZATION_SKIPPED,
                    opplysningstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                    endringstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                ) shouldBeExactly 1.0

                val logMessage = logAppender.list.joinToString("\n") { it.formattedMessage }
                logMessage.contains("offset=42") shouldBe true
                logMessage.contains("partition=0") shouldBe true
                logMessage.contains("12345678910") shouldBe false
                logMessage.contains("Ola Nordmann") shouldBe false
            }

            it("retries transient serialization failures without committing offsets") {
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    retryDelaySeconds = 0,
                )
                every { kafkaConsumer.poll(any<Duration>()) } throws SerializationException("schema registry unavailable for 12345678910")

                runTest {
                    consumer.pollAndProcess(kafkaConsumer)
                }

                verify(exactly = 0) { kafkaConsumer.seek(any<TopicPartition>(), any<Long>()) }
                verify(exactly = 0) { kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>()) }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_SERIALIZATION_ERROR,
                    opplysningstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                    endringstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                ) shouldBeExactly 1.0
                val logMessage = logAppender.list.joinToString("\n") { it.formattedMessage }
                logMessage.contains("12345678910") shouldBe false
                logMessage.contains("schema registry unavailable") shouldBe false
            }
        }

        describe("listen") {
            it("does not subscribe or poll when toggle is disabled") {
                val disabledConsumer = PdlLeesahConsumer(
                    kafkaConsumer = kafkaConsumer,
                    scope = testScope,
                    env = OtherEnvironmentProperties.createForLocal().copy(pdlLeesahConsumerEnabled = false),
                    retryDelaySeconds = 0,
                )

                disabledConsumer.listen()

                verify(exactly = 0) { kafkaConsumer.subscribe(any<List<String>>()) }
                verify(exactly = 0) { kafkaConsumer.poll(any<Duration>()) }
            }
        }
    })

private fun createConsumer(
    kafkaConsumer: KafkaConsumer<String, Personhendelse>,
    retryDelaySeconds: Long = 0,
): PdlLeesahConsumer = PdlLeesahConsumer(
    kafkaConsumer = kafkaConsumer,
    scope = CoroutineScope(EmptyCoroutineContext),
    env = OtherEnvironmentProperties.createForLocal(),
    retryDelaySeconds = retryDelaySeconds,
)

private fun removePdlLeesahMetrics() {
    METRICS_REGISTRY.find(PdlLeesahConsumer.PDL_LEESAH_HENDELSE_TOTAL)
        .meters()
        .forEach(METRICS_REGISTRY::remove)
}

private fun metricCount(
    result: String,
    opplysningstype: String,
    endringstype: String,
): Double = METRICS_REGISTRY.find(PdlLeesahConsumer.PDL_LEESAH_HENDELSE_TOTAL)
    .tags(
        "opplysningstype",
        opplysningstype,
        "endringstype",
        endringstype,
        "result",
        result,
    )
    .counter()
    ?.count() ?: 0.0

private fun consumerRecords(vararg records: ConsumerRecord<String, Personhendelse>): ConsumerRecords<String, Personhendelse> = ConsumerRecords(
    records.groupBy { TopicPartition(it.topic(), it.partition()) }
)

private fun consumerRecord(
    offset: Long,
    key: String = "key-$offset",
    value: Personhendelse?,
): ConsumerRecord<String, Personhendelse> = ConsumerRecord(
    PdlLeesahConsumer.PDL_LEESAH_TOPIC,
    0,
    offset,
    key,
    value,
)

private fun personhendelse(
    opplysningstype: String = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
    personidenter: List<String> = listOf("12345678910"),
    navn: Navn? = navn(),
): Personhendelse = Personhendelse(
    "hendelse-id",
    personidenter,
    "PDL",
    Instant.parse("2026-01-01T12:00:00Z"),
    opplysningstype,
    Endringstype.KORRIGERT,
    null,
    navn,
)

private fun navn(
    fornavn: String = "Ola",
    mellomnavn: String? = null,
    etternavn: String = "Nordmann",
    originaltNavn: OriginaltNavn? = null,
): Navn = Navn(
    fornavn,
    mellomnavn,
    etternavn,
    null,
    originaltNavn,
)
