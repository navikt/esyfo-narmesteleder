package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PersistNarmestelederrelasjonerFromLeesahUseCase
import no.nav.syfo.narmestelederrelasjon.application.RecordingLeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.RecordingNarmestelederRegisterMetrics
import no.nav.syfo.narmestelederrelasjon.application.validated
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import kotlin.coroutines.EmptyCoroutineContext

class PersistNarmestelederRegisterFromLeesahConsumerTest :
    FunSpec({
        val objectMapper = jacksonMapper()
        val kafkaConsumer = mockk<KafkaConsumer<String, String?>>(relaxed = true)
        val producer = mockk<NarmestelederLeesahProducer>(relaxed = true)
        val repository = RecordingLeesahNarmestelederrelasjonRepository()

        fun consumer(
            repository: LeesahNarmestelederrelasjonRepository = RecordingLeesahNarmestelederrelasjonRepository(),
            commitOnAllErrors: Boolean = false,
        ) = PersistNarmestelederRegisterFromLeesahConsumer(
            persistFromLeesah = PersistNarmestelederrelasjonerFromLeesahUseCase(repository, RecordingNarmestelederRegisterMetrics()),
            narmestelederLeesahProducer = producer,
            jacksonMapper = objectMapper,
            kafkaConsumer = kafkaConsumer,
            scope = CoroutineScope(EmptyCoroutineContext),
            env = OtherEnvironmentProperties.createForLocal(),
        ).also { it.commitOnAllErrors = commitOnAllErrors }

        fun json(message: NarmestelederLeesahKafkaMessage) = objectMapper.writeValueAsString(message)

        beforeTest {
            clearMocks(kafkaConsumer, producer)
            repository.calls.clear()
        }

        context("processBatch") {
            test("persists valid records, republishes them and tombstones with original key and value, then commits") {
                val valid = narmestelederLeesahKafkaMessage()
                val records = consumerRecords(
                    consumerRecord(offset = 1, key = "valid-key", value = json(valid)),
                    consumerRecord(offset = 2, key = "invalid-key", value = json(narmestelederLeesahKafkaMessage().copy(fnr = "123"))),
                    consumerRecord(offset = 3, key = "malformed-key", value = "{not-valid-json"),
                    consumerRecord(offset = 4, key = "tombstone-key", value = null),
                )

                consumer(repository).processBatch(records, kafkaConsumer)

                repository.calls.single().relasjoner shouldBe listOf(valid.toLeesahNarmestelederrelasjon().validated())
                verifyOrder {
                    producer.sendLeesahBatch(
                        listOf(
                            NarmestelederLeesahProducerRecord(key = "valid-key", value = json(valid)),
                            NarmestelederLeesahProducerRecord(key = "tombstone-key", value = null),
                        ),
                    )
                    kafkaConsumer.commitSync()
                }
            }

            test("does not publish or commit when persistence fails") {
                val failing = LeesahNarmestelederrelasjonRepository { _, _ -> error("database down") }
                val records = consumerRecords(consumerRecord(offset = 1, value = json(narmestelederLeesahKafkaMessage())))

                shouldThrow<IllegalStateException> { consumer(failing).processBatch(records, kafkaConsumer) }

                verify(exactly = 0) { producer.sendLeesahBatch(any()) }
                verify(exactly = 0) { kafkaConsumer.commitSync() }
            }

            test("discards the batch and commits when persistence fails and commitOnAllErrors is enabled") {
                val failing = LeesahNarmestelederrelasjonRepository { _, _ -> error("database down") }
                val records = consumerRecords(consumerRecord(offset = 1, value = json(narmestelederLeesahKafkaMessage())))

                consumer(failing, commitOnAllErrors = true).processBatch(records, kafkaConsumer)

                verify(exactly = 0) { producer.sendLeesahBatch(any()) }
                verify(exactly = 1) { kafkaConsumer.commitSync() }
            }

            listOf(false, true).forEach { commitOnAllErrors ->
                test("does not commit when publishing fails after persistence (commitOnAllErrors=$commitOnAllErrors)") {
                    every { producer.sendLeesahBatch(any()) } throws IllegalStateException("boom")
                    val records = consumerRecords(consumerRecord(offset = 1, value = json(narmestelederLeesahKafkaMessage())))

                    shouldThrow<IllegalStateException> {
                        consumer(repository, commitOnAllErrors).processBatch(records, kafkaConsumer)
                    }

                    repository.calls.size shouldBe 1
                    verify(exactly = 0) { kafkaConsumer.commitSync() }
                }
            }
        }

        context("stop") {
            test("wakes up, unsubscribes and closes the consumer before returning") {
                val subscribeStarted = CompletableDeferred<Unit>()
                val pollReleased = CompletableDeferred<Unit>()
                every { kafkaConsumer.subscribe(any<List<String>>()) } answers { subscribeStarted.complete(Unit) }
                every { kafkaConsumer.poll(any()) } answers {
                    runBlocking { pollReleased.await() }
                    throw WakeupException()
                }
                every { kafkaConsumer.wakeup() } answers { pollReleased.complete(Unit) }
                val consumer = consumer()

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
    })

private fun consumerRecords(vararg records: ConsumerRecord<String, String?>): ConsumerRecords<String, String?> = ConsumerRecords(
    records.groupBy { TopicPartition(it.topic(), it.partition()) },
    emptyMap(),
)

private fun consumerRecord(offset: Long, key: String = "key-$offset", value: String?): ConsumerRecord<String, String?> =
    ConsumerRecord(TEAMSYKMELDING_NL_LEESAH_TOPIC, 0, offset, key, value)
