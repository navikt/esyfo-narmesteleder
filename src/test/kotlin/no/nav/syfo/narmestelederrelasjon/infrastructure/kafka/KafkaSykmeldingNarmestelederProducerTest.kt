package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import faker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSingleton
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import linemanager
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.KafkaSykmeldingNarmestelederProducer.Companion.SYKEMELDING_NL_TOPIC
import no.nav.syfo.platform.kafka.JacksonKafkaSerializer
import org.apache.kafka.common.serialization.Serializer
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ExecutionException

class KafkaSykmeldingNarmestelederProducerTest :
    FunSpec({
        fun nlAvbrutt() = NlAvbrutt(sykmeldtFnr = "12345678901", orgnummer = "123456789")

        fun fakeNarmestelederProducer(autoComplete: Boolean = true) = fakeKafkaProducer(
            Serializer<NarmestelederResponseKafkaMessage> { topic, message -> JacksonKafkaSerializer().serialize(topic, message) },
            autoComplete,
        )

        fun nlResponse(): NlResponse {
            val relasjon = linemanager()
            val linemanagerName = Navn(faker.name().firstName(), null, faker.name().lastName())
            val sykmeldtName = Navn(faker.name().firstName(), null, faker.name().lastName())
            return NlResponse(
                orgnummer = relasjon.orgNumber.value,
                leder = Leder(
                    fnr = relasjon.manager.nationalIdentificationNumber.value,
                    mobil = relasjon.manager.mobile,
                    epost = relasjon.manager.email,
                    fornavn = linemanagerName.fornavn,
                    etternavn = linemanagerName.etternavn,
                ),
                sykmeldt = Sykmeldt(
                    fnr = relasjon.employeeIdentificationNumber.value,
                    navn = listOfNotNull(
                        sykmeldtName.fornavn,
                        sykmeldtName.mellomnavn,
                        sykmeldtName.etternavn,
                    ).joinToString(" "),
                ),
                utbetalesLonn = null,
            )
        }

        context("sendSykemeldingNLRelasjon") {
            test("Calls send on Producer with ProducerRecord containing NlResponse") {
                // Arrange
                val kafkaProducer = fakeNarmestelederProducer()
                val sykmeldingNL = nlResponse()

                // Act
                KafkaSykmeldingNarmestelederProducer(kafkaProducer).sendSykmeldingNLRelasjon(sykmeldingNL, NlResponseSource.LPS)

                // Assert
                val record = kafkaProducer.history().shouldBeSingleton().single()
                record.topic() shouldBe SYKEMELDING_NL_TOPIC
                record.key() shouldBe sykmeldingNL.orgnummer
                val message = record.value().shouldBeInstanceOf<NarmestelederRelationResponseKafkaMessage>()
                message.kafkaMetadata.source shouldBe NlResponseSource.LPS.source
                message.nlResponse shouldBe sykmeldingNL
            }

            test("waits for the broker to acknowledge the record") {
                val kafkaProducer = fakeNarmestelederProducer(autoComplete = false)
                val rejection = IllegalStateException("rejected by broker")

                val failure = kafkaProducer.rejectAcknowledgement(rejection) {
                    KafkaSykmeldingNarmestelederProducer(kafkaProducer).sendSykmeldingNLRelasjon(nlResponse(), NlResponseSource.LPS)
                }

                failure.shouldBeInstanceOf<ExecutionException>().cause shouldBe rejection
            }
        }
        context("sendSykemeldingNLBrudd") {
            test("Calls send on Producer with ProducerRecord containing NlAvbrutt") {
                // Arrange
                val kafkaProducer = fakeNarmestelederProducer()
                val now = OffsetDateTime.now(ZoneOffset.UTC)
                val avbrutt = nlAvbrutt()

                // Act
                KafkaSykmeldingNarmestelederProducer(kafkaProducer).sendSykmldingNLBrudd(avbrutt, NlResponseSource.LPS)

                // Assert
                val record = kafkaProducer.history().shouldBeSingleton().single()
                record.topic() shouldBe SYKEMELDING_NL_TOPIC
                record.key() shouldBe avbrutt.orgnummer
                val message = record.value().shouldBeInstanceOf<NarmestelederAvbruddResponseKafkaMessage>()
                message.kafkaMetadata.source shouldBe NlResponseSource.LPS.source
                message.nlAvbrutt.orgnummer shouldBe avbrutt.orgnummer
                message.nlAvbrutt.sykmeldtFnr shouldBe avbrutt.sykmeldtFnr
                message.nlAvbrutt.aktivTom shouldBeAfter now
            }

            test("waits for the broker to acknowledge the record") {
                val kafkaProducer = fakeNarmestelederProducer(autoComplete = false)
                val rejection = IllegalStateException("rejected by broker")

                val failure = kafkaProducer.rejectAcknowledgement(rejection) {
                    KafkaSykmeldingNarmestelederProducer(kafkaProducer).sendSykmldingNLBrudd(nlAvbrutt(), NlResponseSource.LPS)
                }

                failure.shouldBeInstanceOf<ExecutionException>().cause shouldBe rejection
            }
        }
    })
