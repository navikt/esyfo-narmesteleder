package no.nav.syfo.narmestelederbehov.infrastructure.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import no.nav.syfo.integration.aareg.AAREG
import no.nav.syfo.narmestelederbehov.application.FakeBehovRepository
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahUseCase
import no.nav.syfo.platform.api.toUpstreamUnavailableException
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import kotlin.coroutines.EmptyCoroutineContext

class LeesahNLKafkaConsumerLoggingTest :
    FunSpec({
        val productionLogs = extension(ProductionLogs(LeesahNLKafkaConsumer::class.java))
        lateinit var logs: ProductionLogCapture
        beforeTest { logs = productionLogs.capture() }
        afterTest { logs.close() }

        test("record processing failures retain partition and offset without payloads") {
            val consumer = LeesahNLKafkaConsumer(
                kafkaConsumer = mockk<KafkaConsumer<String, String>>(),
                jacksonMapper = jacksonObjectMapper(),
                handler = unusedHandler(),
                fulfillBehovFromLeesah = unusedFulfillBehovFromLeesah(),
                scope = CoroutineScope(EmptyCoroutineContext),
            )
            consumer.logConsumerFailure(LeesahRecordProcessingException(3, 42, IllegalStateException("private-canary")))

            val event = logs.logs.single()
            val fields = event.fields
            fields["event_type"] shouldBe "kafka_consumer_failed"
            fields["consumer"] shouldBe "NL_LEESAH"
            fields["partition"] shouldBe 3
            fields["offset"] shouldBe 42
            event.serialized.contains("private-canary") shouldBe false
        }

        test("record processing failures name the unavailable upstream reported by the use case") {
            val consumer = LeesahNLKafkaConsumer(
                kafkaConsumer = mockk<KafkaConsumer<String, String>>(),
                jacksonMapper = jacksonObjectMapper(),
                handler = unusedHandler(),
                fulfillBehovFromLeesah = unusedFulfillBehovFromLeesah(),
                scope = CoroutineScope(EmptyCoroutineContext),
            )
            val aaregUnavailable = UpstreamFailure(AAREG, UpstreamFailureStage.REQUEST, null, IllegalStateException("private-canary"))
                .toUpstreamUnavailableException()

            consumer.logConsumerFailure(LeesahRecordProcessingException(3, 42, aaregUnavailable))

            val fields = logs.logs.single().fields
            fields["event_type"] shouldBe "kafka_consumer_failed"
            fields["upstream"] shouldBe "aareg"
            fields["failure_stage"] shouldBe "request"
        }

        test("fatal errors are rethrown unchanged even when committing processing errors") {
            val consumer = LeesahNLKafkaConsumer(
                kafkaConsumer = mockk<KafkaConsumer<String, String>>(),
                jacksonMapper = jacksonObjectMapper(),
                handler = unusedHandler(),
                fulfillBehovFromLeesah = unusedFulfillBehovFromLeesah(),
                scope = CoroutineScope(EmptyCoroutineContext),
            )
            consumer.commitOnAllErrors = true
            val fatal = LinkageError("synthetic fatal error")
            val record = ConsumerRecord(TEAMSYKMELDING_NL_LEESAH_TOPIC, 3, 42L, "key", "value")

            shouldThrow<LinkageError> { consumer.handleProccessingError(record, fatal) } shouldBe fatal
        }
    })

private fun unusedHandler() = NlBehovLeesahHandler { error("Unexpected CreateNarmestelederbehov") }

private fun unusedFulfillBehovFromLeesah() = FulfillNarmestelederbehovFromLeesahUseCase(
    behovRepository = FakeBehovRepository(behov = null),
    metrics = { error("Unexpected fulfillment metric") },
    dialog = { error("Unexpected dialog completion") },
)
