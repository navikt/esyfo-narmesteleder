package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import faker
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import linemanager
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.testcontainers.shaded.com.google.common.util.concurrent.SettableFuture
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class KafkaSykmeldingNarmestelederProducerTest :
    DescribeSpec({
        val kafkaProducerMock = mockk<KafkaProducer<String, NarmestelederResponseKafkaMessage>>()
        val producer = KafkaSykmeldingNarmestelederProducer(kafkaProducerMock)

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }
        describe("sendSykemeldingNLRelasjon") {
            it("Calls send on Producer with ProducerRecord containing NlResponse") {
                // Arrange
                val relasjon = linemanager()
                val linemanagerPerson = Person(
                    nationalIdentificationNumber = PersonalIdentificationNumber(faker.numerify("###########")),
                    name =
                    Navn(faker.name().firstName(), null, faker.name().lastName())
                )
                val sykmeldtPerson = Person(
                    nationalIdentificationNumber = relasjon.employeeIdentificationNumber,
                    name = Navn(faker.name().firstName(), null, faker.name().lastName())
                )
                val recordMetadata = createRecordMetadata()

                val futureMock = mockk<SettableFuture<RecordMetadata>>()
                coEvery { futureMock.get() } returns recordMetadata
                coEvery { kafkaProducerMock.send(any<ProducerRecord<String, NarmestelederResponseKafkaMessage>>()) } returns futureMock

                val sykmeldingNL = NlResponse(
                    orgnummer = relasjon.orgNumber.value,
                    leder = Leder(
                        fnr = relasjon.manager.nationalIdentificationNumber.value,
                        mobil = relasjon.manager.mobile,
                        epost = relasjon.manager.email,
                        fornavn = linemanagerPerson.name.fornavn,
                        etternavn = linemanagerPerson.name.etternavn,
                    ),
                    sykmeldt = Sykmeldt(
                        fnr = sykmeldtPerson.nationalIdentificationNumber.value,
                        navn = listOfNotNull(
                            sykmeldtPerson.name.fornavn,
                            sykmeldtPerson.name.mellomnavn,
                            sykmeldtPerson.name.etternavn,
                        ).joinToString(" "),
                    ),
                    utbetalesLonn = null,
                )

                // Act
                producer.sendSykmeldingNLRelasjon(sykmeldingNL, NlResponseSource.LPS)

                // Assert
                verify(exactly = 1) {
                    kafkaProducerMock.send(
                        withArg {
                            it.shouldBeInstanceOf<ProducerRecord<String, NarmestelederRelationResponseKafkaMessage>>()
                            it.value().kafkaMetadata.source shouldBe NlResponseSource.LPS.source
                            it.value().nlResponse shouldBe sykmeldingNL
                        }
                    )
                }
                verify(exactly = 1) { futureMock.get() }
            }
        }
        describe("sendSykemeldingNLBrudd") {
            it("Calls send on Producer with ProducerRecord containing NlAvbrutt") {
                // Arrange
                val recordMetadata = createRecordMetadata()
                val now = OffsetDateTime.now(ZoneOffset.UTC)
                val avbryt = NlAvbrutt(sykmeldtFnr = "12345678901", orgnummer = "123456789")

                val futureMock = mockk<SettableFuture<RecordMetadata>>()
                coEvery { futureMock.get() } returns recordMetadata
                coEvery { kafkaProducerMock.send(any<ProducerRecord<String, NarmestelederResponseKafkaMessage>>()) } returns futureMock

                // Act
                producer.sendSykmldingNLBrudd(avbryt, NlResponseSource.LPS)

                // Assert
                verify(exactly = 1) {
                    kafkaProducerMock.send(
                        withArg {
                            it.shouldBeInstanceOf<ProducerRecord<String, NarmestelederAvbruddResponseKafkaMessage>>()
                            it.value().kafkaMetadata.source shouldBe NlResponseSource.LPS.source
                            it.value().nlAvbrutt shouldNotBe null
                            it.value().nlAvbrutt.orgnummer shouldBe avbryt.orgnummer
                            it.value().nlAvbrutt.sykmeldtFnr shouldBe avbryt.sykmeldtFnr
                            it.value().nlAvbrutt.aktivTom shouldBeAfter now
                        }
                    )
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
