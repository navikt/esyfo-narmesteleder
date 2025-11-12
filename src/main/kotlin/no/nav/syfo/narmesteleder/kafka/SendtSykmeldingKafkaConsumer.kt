package no.nav.syfo.narmesteleder.kafka

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.application.kafka.suspendingPoll
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import kotlin.String
import kotlin.time.Duration.Companion.seconds

const val SENDT_SYKMELDING_TOPIC = "teamsykmelding.syfo-sendt-sykmelding"

class SendtSykmeldingKafkaConsumer(
    private val kafkaConsumer: KafkaConsumer<String, String>,
    private val scope: CoroutineScope,
) : KafkaListener {
    private lateinit var job: Job

    override fun listen() {
        logger.info("Starting $SENDT_SYKMELDING_TOPIC consumer")
        job = scope.launch(Dispatchers.IO + CoroutineName("sendt-sykmelding-consumer")) {

            while (job.isActive) {
                try {
                    kafkaConsumer.subscribe(listOf(SENDT_SYKMELDING_TOPIC))
                    kafkaConsumer.suspendingPoll(POLL_DURATION_SECONDS.seconds)
                        .forEach { record: ConsumerRecord<String, String?> ->
                            logger.info("Received record with key: ${record.key()}\nand value ${record.value()}")
                        }
                } catch (e: Exception) {
                    logger.error(
                        "Error running kafka consumer. Waiting $DELAY_ON_ERROR_SECONDS seconds for retry.", e
                    )
                    kafkaConsumer.unsubscribe()
                    delay(DELAY_ON_ERROR_SECONDS.seconds)
                }
            }
            kafkaConsumer.close()
            logger.info("Exited $SENDT_SYKMELDING_TOPIC consumer loop")
        }
    }

    override suspend fun stop() {
        if (!::job.isInitialized) error("Consumer not started!")

        logger.info("Preparing shutdown")
        logger.info("Stopping consuming topic $SENDT_SYKMELDING_TOPIC")

        job.cancelAndJoin()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SendtSykmeldingKafkaConsumer::class.java)
        private const val DELAY_ON_ERROR_SECONDS = 60L
        private const val POLL_DURATION_SECONDS = 1L
    }
}
