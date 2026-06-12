package no.nav.syfo.pdl.kafka

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
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
import no.nav.syfo.pdl.exception.PdlRequestException
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
        val pdlLeesahNameUpdateService = mockk<PdlLeesahNameUpdateService>()
        val testScope = CoroutineScope(EmptyCoroutineContext)
        val logAppender = ListAppender<ILoggingEvent>()
        val logger = LoggerFactory.getLogger(PdlLeesahConsumer::class.java.name) as Logger

        beforeSpec {
            logger.level = Level.INFO
            logAppender.start()
            logger.addAppender(logAppender)
        }

        afterSpec {
            logger.detachAppender(logAppender)
        }

        beforeTest {
            clearMocks(kafkaConsumer, pdlLeesahNameUpdateService)
            coEvery { pdlLeesahNameUpdateService.processNameChanges(any()) } returns PdlLeesahNameUpdateResult()
            logAppender.list.clear()
            removePdlLeesahMetrics()
            removePdlLeesahPersonUpdateMetrics()
        }

        describe("processRecords") {
            it("counts tombstone and ignored records in polls without relevant events and commits offsets") {
                val consumer = createConsumer(kafkaConsumer = kafkaConsumer)
                val records = consumerRecords(
                    consumerRecord(
                        offset = 0L,
                        value = null,
                    ),
                    consumerRecord(
                        offset = 1L,
                        value = personhendelse(
                            opplysningstype = "DOEDSFALL_V1",
                            navn = null,
                        ),
                    ),
                )

                runTest {
                    consumer.processRecords(records, kafkaConsumer)
                }

                verify(exactly = 1) {
                    kafkaConsumer.commitSync(
                        mapOf(TopicPartition(PdlLeesahConsumer.PDL_LEESAH_TOPIC, 0) to OffsetAndMetadata(2))
                    )
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_TOMBSTONE,
                    opplysningstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                    endringstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                ) shouldBeExactly 1.0
                metricCount(
                    result = PdlLeesahConsumer.RESULT_IGNORED,
                    opplysningstype = "DOEDSFALL_V1",
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 1.0
            }

            it("processes relevant name events in one batch, logs only structural fields and commits offsets") {
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
                )
                val fnr1 = "12345678910"
                val fnr2 = "01987654321"
                val fornavn = "Ola"
                val etternavn = "Nordmann"
                val records = consumerRecords(
                    consumerRecord(
                        offset = 2L,
                        key = fnr1,
                        value = personhendelse(
                            personidenter = listOf(fnr1, fnr2),
                            navn = navn(
                                fornavn = fornavn,
                                mellomnavn = "Mellom",
                                etternavn = etternavn,
                                originaltNavn = OriginaltNavn("Zyxel", null, "Navneverdi"),
                            ),
                        ),
                    ),
                    consumerRecord(
                        offset = 3L,
                        key = fnr2,
                        value = personhendelse(
                            personidenter = listOf(fnr2, fnr1),
                            navn = navn(
                                fornavn = "Kari",
                                mellomnavn = null,
                                etternavn = "Nordmann",
                            ),
                        ),
                    ),
                )

                coEvery {
                    pdlLeesahNameUpdateService.processNameChanges(listOf(fnr1, fnr2, fnr2, fnr1))
                } returns PdlLeesahNameUpdateResult(updatedCount = 2)

                runTest {
                    consumer.processRecords(records, kafkaConsumer)
                }

                verify(exactly = 1) {
                    kafkaConsumer.commitSync(
                        mapOf(TopicPartition(PdlLeesahConsumer.PDL_LEESAH_TOPIC, 0) to OffsetAndMetadata(4))
                    )
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_PROCESSED,
                    opplysningstype = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 2.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_UPDATED) shouldBeExactly 2.0

                val logMessage = logAppender.list.joinToString("\n") { it.formattedMessage }
                logMessage.contains("relevantRecordCount=2") shouldBe true
                logMessage.contains("updatedCount=2") shouldBe true
                logMessage.contains("notFoundInRegisterCount=0") shouldBe true
                logMessage.contains("pdlNotFoundCount=0") shouldBe true
                logMessage.contains("recordsWithFornavn") shouldBe false
                logMessage.contains("recordsWithMellomnavn") shouldBe false
                logMessage.contains("recordsWithEtternavn") shouldBe false
                logMessage.contains("recordsWithOriginaltNavn") shouldBe false
                coVerify(exactly = 1) {
                    pdlLeesahNameUpdateService.processNameChanges(listOf(fnr1, fnr2, fnr2, fnr1))
                }
                logMessage.contains(fnr1) shouldBe false
                logMessage.contains(fnr2) shouldBe false
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

                runTest {
                    consumer.processRecords(records, kafkaConsumer)
                }

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

            it("does not emit success metrics or commit offsets when name update processing fails with PDL request exception") {
                val fnr = "12345678910"
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
                )
                val records = consumerRecords(
                    consumerRecord(offset = 4L, value = null),
                    consumerRecord(
                        offset = 5L,
                        value = personhendelse(
                            opplysningstype = "DOEDSFALL_V1",
                            navn = null,
                        ),
                    ),
                    consumerRecord(
                        offset = 6L,
                        key = fnr,
                        value = personhendelse(
                            personidenter = listOf(fnr),
                            navn = navn(),
                        ),
                    ),
                )
                coEvery {
                    pdlLeesahNameUpdateService.processNameChanges(listOf(fnr))
                } throws PdlRequestException("PDL unavailable for $fnr")

                runTest {
                    shouldThrow<PdlRequestException> {
                        consumer.processRecords(records, kafkaConsumer)
                    }
                }

                verify(exactly = 0) {
                    kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>())
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_TOMBSTONE,
                    opplysningstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                    endringstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                ) shouldBeExactly 0.0
                metricCount(
                    result = PdlLeesahConsumer.RESULT_IGNORED,
                    opplysningstype = "DOEDSFALL_V1",
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 0.0
                metricCount(
                    result = PdlLeesahConsumer.RESULT_PROCESSED,
                    opplysningstype = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_UPDATED) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_NOT_FOUND_IN_REGISTER) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_PDL_NOT_FOUND) shouldBeExactly 0.0
            }

            it("commits tombstones and ignored records together with relevant records only after batch success") {
                val fnr = "12345678910"
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
                )
                val records = consumerRecords(
                    consumerRecord(offset = 6L, value = null),
                    consumerRecord(
                        offset = 7L,
                        value = personhendelse(
                            opplysningstype = "DOEDSFALL_V1",
                            navn = null,
                        ),
                    ),
                    consumerRecord(
                        offset = 8L,
                        key = fnr,
                        value = personhendelse(
                            personidenter = listOf(fnr),
                            navn = navn(),
                        ),
                    ),
                )
                coEvery {
                    pdlLeesahNameUpdateService.processNameChanges(listOf(fnr))
                } returns PdlLeesahNameUpdateResult(updatedCount = 1)

                runTest {
                    consumer.processRecords(records, kafkaConsumer)
                }

                verify(exactly = 1) {
                    kafkaConsumer.commitSync(
                        mapOf(TopicPartition(PdlLeesahConsumer.PDL_LEESAH_TOPIC, 0) to OffsetAndMetadata(9))
                    )
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_TOMBSTONE,
                    opplysningstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                    endringstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                ) shouldBeExactly 1.0
                metricCount(
                    result = PdlLeesahConsumer.RESULT_IGNORED,
                    opplysningstype = "DOEDSFALL_V1",
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 1.0
                metricCount(
                    result = PdlLeesahConsumer.RESULT_PROCESSED,
                    opplysningstype = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 1.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_UPDATED) shouldBeExactly 1.0
            }

            it("processes relevant name events with empty personidenter and still commits offsets") {
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
                )
                val records = consumerRecords(
                    consumerRecord(
                        offset = 9L,
                        value = personhendelse(personidenter = emptyList()),
                    ),
                )
                coEvery { pdlLeesahNameUpdateService.processNameChanges(emptyList()) } returns PdlLeesahNameUpdateResult()

                runTest {
                    consumer.processRecords(records, kafkaConsumer)
                }

                coVerify(exactly = 1) { pdlLeesahNameUpdateService.processNameChanges(emptyList()) }
                verify(exactly = 1) {
                    kafkaConsumer.commitSync(
                        mapOf(TopicPartition(PdlLeesahConsumer.PDL_LEESAH_TOPIC, 0) to OffsetAndMetadata(10))
                    )
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_PROCESSED,
                    opplysningstype = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 1.0
            }

            it("groups processed metrics by opplysningstype and endringstype while sending all personidenter in one service call") {
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
                )
                val fnr1 = "12345678910"
                val fnr2 = "01987654321"
                val records = consumerRecords(
                    consumerRecord(
                        offset = 10L,
                        value = personhendelse(
                            personidenter = listOf(fnr1),
                            endringstype = Endringstype.KORRIGERT,
                        ),
                    ),
                    consumerRecord(
                        offset = 11L,
                        value = personhendelse(
                            personidenter = listOf(fnr2),
                            endringstype = Endringstype.OPPRETTET,
                        ),
                    ),
                )
                coEvery {
                    pdlLeesahNameUpdateService.processNameChanges(listOf(fnr1, fnr2))
                } returns PdlLeesahNameUpdateResult(updatedCount = 2)

                runTest {
                    consumer.processRecords(records, kafkaConsumer)
                }

                coVerify(exactly = 1) {
                    pdlLeesahNameUpdateService.processNameChanges(listOf(fnr1, fnr2))
                }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_PROCESSED,
                    opplysningstype = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 1.0
                metricCount(
                    result = PdlLeesahConsumer.RESULT_PROCESSED,
                    opplysningstype = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
                    endringstype = Endringstype.OPPRETTET.toString(),
                ) shouldBeExactly 1.0
            }

            it("propagates commit failures after successful service call without emitting success metrics") {
                val fnr = "12345678910"
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
                )
                val records = consumerRecords(
                    consumerRecord(offset = 12L, value = null),
                    consumerRecord(
                        offset = 13L,
                        value = personhendelse(
                            opplysningstype = "DOEDSFALL_V1",
                            navn = null,
                        ),
                    ),
                    consumerRecord(
                        offset = 14L,
                        value = personhendelse(personidenter = listOf(fnr)),
                    ),
                )
                coEvery {
                    pdlLeesahNameUpdateService.processNameChanges(listOf(fnr))
                } returns PdlLeesahNameUpdateResult(updatedCount = 1)
                every {
                    kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>())
                } throws RuntimeException("commit failed for $fnr")

                runTest {
                    shouldThrow<RuntimeException> {
                        consumer.processRecords(records, kafkaConsumer)
                    }
                }

                coVerify(exactly = 1) { pdlLeesahNameUpdateService.processNameChanges(listOf(fnr)) }
                metricCount(
                    result = PdlLeesahConsumer.RESULT_TOMBSTONE,
                    opplysningstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                    endringstype = PdlLeesahConsumer.METRIC_UNKNOWN_VALUE,
                ) shouldBeExactly 0.0
                metricCount(
                    result = PdlLeesahConsumer.RESULT_IGNORED,
                    opplysningstype = "DOEDSFALL_V1",
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 0.0
                metricCount(
                    result = PdlLeesahConsumer.RESULT_PROCESSED,
                    opplysningstype = PdlLeesahConsumer.NAVN_OPPLYSNINGSTYPE,
                    endringstype = Endringstype.KORRIGERT.toString(),
                ) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_UPDATED) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_NOT_FOUND_IN_REGISTER) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_PDL_NOT_FOUND) shouldBeExactly 0.0
                logAppender.list.none {
                    it.formattedMessage.contains("Processed PDL Leesah name event batch")
                } shouldBe true
            }
        }

        describe("pollAndProcess") {
            it("skips poison-pill records deterministically without logging payload details") {
                val consumer = createConsumer(
                    kafkaConsumer = kafkaConsumer,
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
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
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
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
                    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
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
    pdlLeesahNameUpdateService: PdlLeesahNameUpdateService = mockk<PdlLeesahNameUpdateService>().also {
        coEvery { it.processNameChanges(any()) } returns PdlLeesahNameUpdateResult()
    },
    retryDelaySeconds: Long = 0,
): PdlLeesahConsumer = PdlLeesahConsumer(
    kafkaConsumer = kafkaConsumer,
    scope = CoroutineScope(EmptyCoroutineContext),
    env = OtherEnvironmentProperties.createForLocal(),
    pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
    retryDelaySeconds = retryDelaySeconds,
)

private fun removePdlLeesahMetrics() {
    METRICS_REGISTRY.find(PdlLeesahConsumer.PDL_LEESAH_HENDELSE_TOTAL)
        .meters()
        .forEach(METRICS_REGISTRY::remove)
}

private fun removePdlLeesahPersonUpdateMetrics() {
    METRICS_REGISTRY.find(PDL_LEESAH_PERSON_UPDATE_TOTAL)
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

private fun personUpdateMetricCount(result: String): Double = METRICS_REGISTRY.find(PDL_LEESAH_PERSON_UPDATE_TOTAL)
    .tag("result", result)
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
    endringstype: Endringstype = Endringstype.KORRIGERT,
    navn: Navn? = navn(),
): Personhendelse = Personhendelse(
    "hendelse-id",
    personidenter,
    "PDL",
    Instant.parse("2026-01-01T12:00:00Z"),
    opplysningstype,
    endringstype,
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
