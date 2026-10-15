package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import no.nav.person.pdl.leesah.Endringstype
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.person.pdl.leesah.navn.Navn
import no.nav.person.pdl.leesah.navn.OriginaltNavn
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.RelationPersonNameUpdateResult
import no.nav.syfo.narmestelederrelasjon.application.UpdateRelationPersonNamesUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerRelationPersonNameUpdateMetrics.Companion.RESULT_NOT_FOUND_IN_REGISTER
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerRelationPersonNameUpdateMetrics.Companion.RESULT_PDL_NOT_FOUND
import no.nav.syfo.narmestelederrelasjon.infrastructure.MicrometerRelationPersonNameUpdateMetrics.Companion.RESULT_UPDATED
import no.nav.syfo.narmestelederrelasjon.infrastructure.PDL_LEESAH_PERSON_UPDATE_TOTAL
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.METRIC_UNKNOWN_VALUE
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.NAVN_OPPLYSNINGSTYPE
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.PDL_LEESAH_HENDELSE_TOTAL
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.PDL_LEESAH_TOPIC
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.RESULT_IGNORED
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.RESULT_PROCESSED
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.RESULT_RECORD_DESERIALIZATION_SKIPPED
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.RESULT_SERIALIZATION_ERROR
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer.Companion.RESULT_TOMBSTONE
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
    FunSpec({
        val fnr1 = "12345678910"
        val fnr2 = "01987654321"
        val partition = TopicPartition(PDL_LEESAH_TOPIC, 0)
        val logAppender = ListAppender<ILoggingEvent>()
        val logger = LoggerFactory.getLogger(PdlLeesahConsumer::class.java.name) as Logger

        lateinit var kafkaConsumer: KafkaConsumer<String, Personhendelse>
        lateinit var updateRelationPersonNames: UpdateRelationPersonNamesUseCase
        lateinit var consumer: PdlLeesahConsumer

        beforeSpec {
            logger.level = Level.INFO
            logAppender.start()
            logger.addAppender(logAppender)
        }

        afterSpec {
            logger.detachAppender(logAppender)
        }

        beforeTest {
            kafkaConsumer = mockk(relaxed = true)
            updateRelationPersonNames = mockk()
            coEvery { updateRelationPersonNames.execute(any()) } returns RelationPersonNameUpdateResult.Completed()
            consumer = pdlLeesahConsumer(kafkaConsumer, updateRelationPersonNames)
            logAppender.list.clear()
            removeMetrics(PDL_LEESAH_HENDELSE_TOTAL)
            removeMetrics(PDL_LEESAH_PERSON_UPDATE_TOTAL)
        }

        fun loggedMessages(): String = logAppender.list.joinToString("\n") { it.formattedMessage }

        fun updateReturns(vararg personidenter: String, updatedCount: Int) {
            coEvery { updateRelationPersonNames.execute(personidenter.toList()) } returns RelationPersonNameUpdateResult.Completed(updatedCount = updatedCount)
        }

        fun shouldCommitUpTo(nextOffset: Long) {
            verify(exactly = 1) { kafkaConsumer.commitSync(mapOf(partition to OffsetAndMetadata(nextOffset))) }
        }

        fun shouldNotEmitBatchMetrics() {
            tombstoneCount() shouldBeExactly 0.0
            ignoredCount() shouldBeExactly 0.0
            processedCount() shouldBeExactly 0.0
            personUpdateCount(RESULT_UPDATED) shouldBeExactly 0.0
            personUpdateCount(RESULT_NOT_FOUND_IN_REGISTER) shouldBeExactly 0.0
            personUpdateCount(RESULT_PDL_NOT_FOUND) shouldBeExactly 0.0
        }

        test("logConsumerFailure logs incomplete PDL batch counts without personal data") {
            consumer.logConsumerFailure(IncompleteBulkPersonLookupException(requestedCount = 10, missingCount = 2))

            val fields = logAppender.list.single().fields()
            fields["event_type"] shouldBe "kafka_consumer_failed"
            fields["consumer"] shouldBe "PDL_LEESAH"
            fields["error_code"] shouldBe "PDL_BULK_RESPONSE_INCOMPLETE"
            fields["requested_count"] shouldBe 10
            fields["missing_count"] shouldBe 2
            fields.toString() shouldNotContain fnr1
        }

        test("logConsumerFailure logs direct failures; call sites handle cancellation") {
            consumer.logConsumerFailure(CancellationException("cancelled"))

            logAppender.list.single().fields()["event_type"] shouldBe "kafka_consumer_failed"
        }

        test("logConsumerFailure logs wrapped cancellation as an ordinary retry failure without the message") {
            consumer.logConsumerFailure(IllegalStateException("private-canary", CancellationException("cancelled")))

            val event = logAppender.list.single()
            event.fields()["event_type"] shouldBe "kafka_consumer_failed"
            event.formattedMessage shouldNotContain "private-canary"
        }

        test("processRecords counts tombstones and ignored records and commits offsets without calling the use case") {
            consumer.processRecords(consumerRecords(tombstone(offset = 0L), ignoredRecord(offset = 1L)), kafkaConsumer)

            shouldCommitUpTo(2L)
            tombstoneCount() shouldBeExactly 1.0
            ignoredCount() shouldBeExactly 1.0
            coVerify(exactly = 0) { updateRelationPersonNames.execute(any()) }
        }

        test("processRecords updates relevant name events in one batch, logs only counts and commits offsets") {
            val records = consumerRecords(
                nameRecord(
                    offset = 2L,
                    key = fnr1,
                    personidenter = listOf(fnr1, fnr2),
                    navn = navn(mellomnavn = "Mellom", originaltNavn = OriginaltNavn("Zyxel", null, "Navneverdi")),
                ),
                nameRecord(offset = 3L, key = fnr2, personidenter = listOf(fnr2, fnr1), navn = navn(fornavn = "Kari")),
            )
            updateReturns(fnr1, fnr2, fnr2, fnr1, updatedCount = 2)

            consumer.processRecords(records, kafkaConsumer)

            coVerify(exactly = 1) { updateRelationPersonNames.execute(listOf(fnr1, fnr2, fnr2, fnr1)) }
            shouldCommitUpTo(4L)
            processedCount() shouldBeExactly 2.0
            personUpdateCount(RESULT_UPDATED) shouldBeExactly 2.0
            logAppender.list.map { it.formattedMessage } shouldBe listOf(
                "Processed PDL Leesah name event batch relevantRecordCount=2, updatedCount=2, notFoundInRegisterCount=0, pdlNotFoundCount=0"
            )
        }

        test("processRecords neither commits offsets nor emits metrics when the name update fails") {
            val records = consumerRecords(tombstone(offset = 4L), ignoredRecord(offset = 5L), nameRecord(offset = 6L, key = fnr1))
            coEvery { updateRelationPersonNames.execute(listOf(fnr1)) } throws IllegalStateException("Name update failed")

            shouldThrow<IllegalStateException> { consumer.processRecords(records, kafkaConsumer) }

            verify(exactly = 0) { kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>()) }
            shouldNotEmitBatchMetrics()
        }

        test("processRecords throws an incomplete lookup without committing offsets or emitting metrics so the batch is retried") {
            val records = consumerRecords(tombstone(offset = 4L), ignoredRecord(offset = 5L), nameRecord(offset = 6L, key = fnr1))
            coEvery { updateRelationPersonNames.execute(listOf(fnr1)) } returns RelationPersonNameUpdateResult.LookupIncomplete(requestedCount = 1, missingCount = 1)

            val failure = shouldThrow<IncompleteBulkPersonLookupException> { consumer.processRecords(records, kafkaConsumer) }

            failure.requestedCount shouldBe 1
            failure.missingCount shouldBe 1
            verify(exactly = 0) { kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>()) }
            shouldNotEmitBatchMetrics()
            loggedMessages() shouldNotContain "Processed PDL Leesah name event batch"
        }

        test("processRecords commits tombstones and ignored records together with relevant records after batch success") {
            val records = consumerRecords(tombstone(offset = 6L), ignoredRecord(offset = 7L), nameRecord(offset = 8L, key = fnr1))
            updateReturns(fnr1, updatedCount = 1)

            consumer.processRecords(records, kafkaConsumer)

            shouldCommitUpTo(9L)
            tombstoneCount() shouldBeExactly 1.0
            ignoredCount() shouldBeExactly 1.0
            processedCount() shouldBeExactly 1.0
            personUpdateCount(RESULT_UPDATED) shouldBeExactly 1.0
        }

        test("processRecords passes relevant name events with empty personidenter to the use case and commits offsets") {
            consumer.processRecords(consumerRecords(nameRecord(offset = 9L, personidenter = emptyList())), kafkaConsumer)

            coVerify(exactly = 1) { updateRelationPersonNames.execute(emptyList()) }
            shouldCommitUpTo(10L)
            processedCount() shouldBeExactly 1.0
        }

        test("processRecords groups processed metrics by endringstype while sending all personidenter in one call") {
            val records = consumerRecords(
                nameRecord(offset = 10L, personidenter = listOf(fnr1), endringstype = Endringstype.KORRIGERT),
                nameRecord(offset = 11L, personidenter = listOf(fnr2), endringstype = Endringstype.OPPRETTET),
            )
            updateReturns(fnr1, fnr2, updatedCount = 2)

            consumer.processRecords(records, kafkaConsumer)

            coVerify(exactly = 1) { updateRelationPersonNames.execute(listOf(fnr1, fnr2)) }
            processedCount(Endringstype.KORRIGERT) shouldBeExactly 1.0
            processedCount(Endringstype.OPPRETTET) shouldBeExactly 1.0
        }

        test("processRecords propagates commit failures after a successful update without emitting metrics or logging the batch") {
            val records = consumerRecords(tombstone(offset = 12L), ignoredRecord(offset = 13L), nameRecord(offset = 14L, personidenter = listOf(fnr1)))
            updateReturns(fnr1, updatedCount = 1)
            every { kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>()) } throws RuntimeException("commit failed for $fnr1")

            shouldThrow<RuntimeException> { consumer.processRecords(records, kafkaConsumer) }

            coVerify(exactly = 1) { updateRelationPersonNames.execute(listOf(fnr1)) }
            shouldNotEmitBatchMetrics()
            loggedMessages() shouldNotContain "Processed PDL Leesah name event batch"
        }

        test("pollAndProcess skips poison-pill records deterministically without logging payload details") {
            every { kafkaConsumer.poll(any<Duration>()) } throws RecordDeserializationException(
                RecordDeserializationException.DeserializationExceptionOrigin.VALUE,
                partition,
                42L,
                0L,
                TimestampType.NO_TIMESTAMP_TYPE,
                null,
                null,
                RecordHeaders(),
                "failed to deserialize payload for $fnr1",
                SerializationException("payload contains Ola Nordmann")
            )

            consumer.pollAndProcess(kafkaConsumer)

            verify(exactly = 1) { kafkaConsumer.seek(partition, 43L) }
            shouldCommitUpTo(43L)
            eventCount(RESULT_RECORD_DESERIALIZATION_SKIPPED) shouldBeExactly 1.0
            val fields = logAppender.list.single().fields()
            fields["event_type"] shouldBe "kafka_record_skipped_error"
            fields["reason"] shouldBe "MALFORMED_RECORD"
            fields["offset"] shouldBe 42L
            fields["partition"] shouldBe 0
            fields["failure_kind"] shouldBe "invalid_response"
            fields["cause_type"] shouldBe "SerializationException"
            loggedMessages() shouldNotContain fnr1
            loggedMessages() shouldNotContain "Ola Nordmann"
        }

        test("pollAndProcess retries transient serialization failures without seeking or committing offsets") {
            every { kafkaConsumer.poll(any<Duration>()) } throws SerializationException("schema registry unavailable for $fnr1")

            consumer.pollAndProcess(kafkaConsumer)

            verify(exactly = 0) { kafkaConsumer.seek(any<TopicPartition>(), any<Long>()) }
            verify(exactly = 0) { kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>()) }
            eventCount(RESULT_SERIALIZATION_ERROR) shouldBeExactly 1.0
            loggedMessages() shouldNotContain fnr1
            loggedMessages() shouldNotContain "schema registry unavailable"
        }

        test("listen does not subscribe or poll when the toggle is disabled") {
            val disabled = pdlLeesahConsumer(
                kafkaConsumer,
                updateRelationPersonNames,
                env = OtherEnvironmentProperties.createForLocal().copy(pdlLeesahConsumerEnabled = false),
            )

            disabled.listen()

            verify(exactly = 0) { kafkaConsumer.subscribe(any<List<String>>()) }
            verify(exactly = 0) { kafkaConsumer.poll(any<Duration>()) }
        }

        test("listen logs a fatal error once before rethrowing it") {
            val fatal = LinkageError("private-canary")
            val failure = CompletableDeferred<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, thrown -> failure.complete(thrown) })
            every { kafkaConsumer.poll(any<Duration>()) } throws fatal

            try {
                pdlLeesahConsumer(kafkaConsumer, updateRelationPersonNames, scope = scope).listen()

                withTimeout(5_000) { failure.await() } shouldBe fatal
                val event = logAppender.list.single { it.level == Level.ERROR }
                val fields = event.fields()
                fields["event_type"] shouldBe "kafka_consumer_crashed"
                fields["consumer"] shouldBe "PDL_LEESAH"
                event.formattedMessage shouldNotContain "private-canary"
            } finally {
                scope.coroutineContext[Job]?.cancel()
            }
        }
    })

private fun pdlLeesahConsumer(
    kafkaConsumer: KafkaConsumer<String, Personhendelse>,
    updateRelationPersonNames: UpdateRelationPersonNamesUseCase,
    scope: CoroutineScope = CoroutineScope(EmptyCoroutineContext),
    env: OtherEnvironmentProperties = OtherEnvironmentProperties.createForLocal(),
): PdlLeesahConsumer = PdlLeesahConsumer(
    kafkaConsumer = kafkaConsumer,
    scope = scope,
    env = env,
    updateRelationPersonNames = updateRelationPersonNames,
    retryDelaySeconds = 0,
)

private fun ILoggingEvent.fields(): Map<String, Any?> = keyValuePairs.associate { it.key to it.value }

private fun removeMetrics(name: String) {
    METRICS_REGISTRY.find(name).meters().forEach(METRICS_REGISTRY::remove)
}

private fun eventCount(
    result: String,
    opplysningstype: String = METRIC_UNKNOWN_VALUE,
    endringstype: String = METRIC_UNKNOWN_VALUE,
): Double = METRICS_REGISTRY.find(PDL_LEESAH_HENDELSE_TOTAL)
    .tags("opplysningstype", opplysningstype, "endringstype", endringstype, "result", result)
    .counter()
    ?.count() ?: 0.0

private fun tombstoneCount(): Double = eventCount(RESULT_TOMBSTONE)

private fun ignoredCount(): Double = eventCount(RESULT_IGNORED, IGNORED_OPPLYSNINGSTYPE, Endringstype.KORRIGERT.toString())

private fun processedCount(endringstype: Endringstype = Endringstype.KORRIGERT): Double = eventCount(RESULT_PROCESSED, NAVN_OPPLYSNINGSTYPE, endringstype.toString())

private fun personUpdateCount(result: String): Double = METRICS_REGISTRY.find(PDL_LEESAH_PERSON_UPDATE_TOTAL)
    .tag("result", result)
    .counter()
    ?.count() ?: 0.0

private const val IGNORED_OPPLYSNINGSTYPE = "DOEDSFALL_V1"

private fun consumerRecords(vararg records: ConsumerRecord<String, Personhendelse>): ConsumerRecords<String, Personhendelse> = ConsumerRecords(records.groupBy { TopicPartition(it.topic(), it.partition()) })

private fun consumerRecord(
    offset: Long,
    key: String = "key-$offset",
    value: Personhendelse?,
): ConsumerRecord<String, Personhendelse> = ConsumerRecord(PDL_LEESAH_TOPIC, 0, offset, key, value)

private fun tombstone(offset: Long): ConsumerRecord<String, Personhendelse> = consumerRecord(offset = offset, value = null)

private fun ignoredRecord(offset: Long): ConsumerRecord<String, Personhendelse> = consumerRecord(offset = offset, value = personhendelse(opplysningstype = IGNORED_OPPLYSNINGSTYPE, navn = null))

private fun nameRecord(
    offset: Long,
    key: String = "key-$offset",
    personidenter: List<String> = listOf(key),
    endringstype: Endringstype = Endringstype.KORRIGERT,
    navn: Navn = navn(),
): ConsumerRecord<String, Personhendelse> = consumerRecord(
    offset = offset,
    key = key,
    value = personhendelse(personidenter = personidenter, endringstype = endringstype, navn = navn),
)

private fun personhendelse(
    opplysningstype: String = NAVN_OPPLYSNINGSTYPE,
    personidenter: List<String> = emptyList(),
    endringstype: Endringstype = Endringstype.KORRIGERT,
    navn: Navn?,
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
): Navn = Navn(fornavn, mellomnavn, etternavn, null, originaltNavn)
