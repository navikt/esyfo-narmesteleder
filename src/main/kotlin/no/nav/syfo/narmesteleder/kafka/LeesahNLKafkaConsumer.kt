package no.nav.syfo.narmesteleder.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.application.kafka.pollDurationInMillis
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.service.NarmesteLederLeesahService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory

const val SYKMELDING_NL_TOPIC = "teamsykmelding.syfo-narmesteleder-leesah"

class LeesahNLKafkaConsumer(
    private val kafkaListener: KafkaConsumer<String, String>,
    private val jacksonMapper: ObjectMapper,
    private val nlLeesahService: NarmesteLederLeesahService
) : KafkaListener {
    override suspend fun listen(applicationState: ApplicationState) {
        while (applicationState.ready) {
            try {
                kafkaListener.subscribe(listOf(SYKMELDING_NL_TOPIC))
                start()
            } catch (e: Exception) {
                kafkaListener.unsubscribe()
                delay(DELAY_ON_ERROR_SECONDS.seconds)
            }
        }
    }

    private suspend fun start() {
        log.info("Starting consuming topic $SYKMELDING_NL_TOPIC")
        while (true) {
            kafkaListener.poll(pollDurationInMillis).forEach { record: ConsumerRecord<String, String> ->
                log.info("Received record with key: ${record.key()}")
                processRecord(record)
            }
            delay(POLL_DURATION_SECONDS.seconds)
        }
    }

    private suspend fun processRecord(record: ConsumerRecord<String, String>) {
        try {
            val nlKafkaMessage: NarmestelederLeesahKafkaMessage? = jacksonMapper.readValue(record.value())

            nlKafkaMessage?.let {
                nlLeesahService.processNarmesteLederLeesahMessage(it)
            }

            log.info("Committing offset")
            kafkaListener.commitSync()
        } catch (e: Exception) {
            log.error("Error encountered while processing sykmelding: ${e.message}", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LeesahNLKafkaConsumer::class.java)
        private const val DELAY_ON_ERROR_SECONDS = 60L
        private const val POLL_DURATION_SECONDS = 10L
    }
}
