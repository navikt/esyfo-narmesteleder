package no.nav.syfo.narmestelder.kafka

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import narmesteLederAvkreft
import narmesteLederRelasjon
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.kafka.SykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.INlResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlAvbruddResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlRelationResponseKafkaMessage
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.testcontainers.shaded.com.google.common.util.concurrent.SettableFuture

class SykemeldingNLKafkaProducerTest : DescribeSpec({
    val kafkaProducerMock = mockk<KafkaProducer<String, INlResponseKafkaMessage>>()
    val producer = SykemeldingNLKafkaProducer(kafkaProducerMock)

    beforeTest {
        clearAllMocks()
    }
    describe("sendSykemeldingNLRelasjon") {
        it("Calls send on Producer with ProducerRecord containing NlResponse") {
            // Arrange
            val relasjon = narmesteLederRelasjon()
            val recordMetadata = createRecordMetadata()

            val futureMock = mockk<SettableFuture<RecordMetadata>>()
            coEvery { futureMock.get() } returns recordMetadata
            coEvery { kafkaProducerMock.send(any<ProducerRecord<String, INlResponseKafkaMessage>>()) } returns futureMock

            // Act
            producer.sendSykemeldingNLRelasjon(relasjon.toNlResponse(), NlResponseSource.LPS)

            // Assert
            verify(exactly = 1) {
                kafkaProducerMock.send(withArg {
                    it.shouldBeInstanceOf<ProducerRecord<String, NlRelationResponseKafkaMessage>>()
                    it.value().kafkaMetadata.source shouldBe NlResponseSource.LPS.name
                    it.value().nlResponse shouldNotBe null
                    it.value().nlResponse.leder shouldBe relasjon.leder
                    it.value().nlResponse.orgnummer shouldBe relasjon.organisasjonsnummer
                    it.value().nlResponse.sykmeldt.fnr shouldBe relasjon.sykmeldtFnr
                })
            }
            verify(exactly = 1) { futureMock.get() }
        }
    }
    describe("sendSykemeldingNLBrudd") {
        it("Calls send on Producer with ProducerRecord containing NlAvbrutt") {
            // Arrange
            val avbryt = narmesteLederAvkreft()
            val recordMetadata = createRecordMetadata()
            val now = OffsetDateTime.now(ZoneOffset.UTC)

            val futureMock = mockk<SettableFuture<RecordMetadata>>()
            coEvery { futureMock.get() } returns recordMetadata
            coEvery { kafkaProducerMock.send(any<ProducerRecord<String, INlResponseKafkaMessage>>()) } returns futureMock

            // Act
            producer.sendSykemeldingNLBrudd(avbryt.toNlAvbrutt(), NlResponseSource.LPS)

            // Assert
            verify(exactly = 1) {
                kafkaProducerMock.send(withArg {
                    it.shouldBeInstanceOf<ProducerRecord<String, NlAvbruddResponseKafkaMessage>>()
                    it.value().kafkaMetadata.source shouldBe NlResponseSource.LPS.name
                    it.value().nlAvbrutt shouldNotBe null
                    it.value().nlAvbrutt.orgnummer shouldBe avbryt.organisasjonsnummer
                    it.value().nlAvbrutt.sykmeldtFnr shouldBe avbryt.sykmeldtFnr
                    it.value().nlAvbrutt.aktivTom shouldBeAfter now
                })
            }
            verify(exactly = 1) { futureMock.get() }
        }
    }
})

private fun createRecordMetadata(): RecordMetadata = RecordMetadata(
    TopicPartition("topic", 0),
    0L, // baseOffset
    1,
    LocalDateTime.now().toEpochSecond(ZoneOffset.UTC),
    5,
    10
)
