package no.nav.syfo.narmesteleder.kafka

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.narmesteleder.kafka.model.LeesahStatus
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovCommand
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehovResult
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.kafka.jacksonMapper
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

class LeesahNLKafkaConsumerTest :
    FunSpec({
        val mapper = jacksonMapper()
        val kafkaConsumer = mockk<KafkaConsumer<String, String>>(relaxed = true)
        val commands = mutableListOf<CreateNarmestelederbehovCommand>()
        var result: CreateNarmestelederbehovResult = CreateNarmestelederbehovResult.Disabled
        val createNarmestelederbehov = CreateNarmestelederbehov { command ->
            commands += command
            result
        }

        fun consumer() = LeesahNLKafkaConsumer(
            kafkaConsumer = kafkaConsumer,
            jacksonMapper = mapper,
            handler = NlBehovLeesahHandler(createNarmestelederbehov),
            fulfillBehovFromLeesah = mockk(),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        fun deactivatedLeaderRecord(offset: Long): ConsumerRecords<String, String> {
            val message = NarmestelederLeesahKafkaMessage(
                narmesteLederId = UUID.randomUUID(),
                fnr = "12345678901",
                orgnummer = "999888777",
                narmesteLederFnr = "10987654321",
                narmesteLederTelefonnummer = "99999999",
                narmesteLederEpost = "leder@example.com",
                aktivFom = LocalDate.now().minusDays(30),
                aktivTom = LocalDate.now(),
                arbeidsgiverForskutterer = null,
                timestamp = OffsetDateTime.now(),
                status = LeesahStatus.DEAKTIVERT_LEDER,
            )
            val record = ConsumerRecord(TEAMSYKMELDING_NL_LEESAH_TOPIC, 0, offset, "key", mapper.writeValueAsString(message))
            return ConsumerRecords(mapOf(TopicPartition(TEAMSYKMELDING_NL_LEESAH_TOPIC, 0) to listOf(record)), emptyMap())
        }

        beforeTest {
            clearMocks(kafkaConsumer)
            commands.clear()
        }

        test("does not commit and resubscribes so the record is read again when Aareg is unavailable") {
            result = CreateNarmestelederbehovResult.UpstreamUnavailable(
                UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException()),
            )
            every { kafkaConsumer.poll(any<Duration>()) } returns deactivatedLeaderRecord(offset = 42) andThen ConsumerRecords.empty()
            val unsubscribed = CompletableDeferred<Unit>()
            every { kafkaConsumer.unsubscribe() } answers { unsubscribed.complete(Unit) }
            val consumer = consumer()

            consumer.listen()
            try {
                withTimeout(10.seconds) { unsubscribed.await() }
            } finally {
                consumer.stop()
            }

            commands.size shouldBe 1
            verify(exactly = 0) { kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>()) }
        }

        test("commits the offset when the behov is created") {
            result = CreateNarmestelederbehovResult.Created(NarmestelederbehovId(UUID.randomUUID()))
            every { kafkaConsumer.poll(any<Duration>()) } returns deactivatedLeaderRecord(offset = 42) andThen ConsumerRecords.empty()
            val committed = CompletableDeferred<Map<TopicPartition, OffsetAndMetadata>>()
            every { kafkaConsumer.commitSync(any<Map<TopicPartition, OffsetAndMetadata>>()) } answers { committed.complete(firstArg()) }
            val consumer = consumer()

            consumer.listen()
            val offsets = try {
                withTimeout(10.seconds) { committed.await() }
            } finally {
                consumer.stop()
            }

            commands.size shouldBe 1
            offsets shouldBe mapOf(TopicPartition(TEAMSYKMELDING_NL_LEESAH_TOPIC, 0) to OffsetAndMetadata(43))
            verify(exactly = 0) { kafkaConsumer.unsubscribe() }
        }
    })
