package no.nav.syfo.sykmelding.kafka

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.kafka.jacksonMapper
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
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
    })
