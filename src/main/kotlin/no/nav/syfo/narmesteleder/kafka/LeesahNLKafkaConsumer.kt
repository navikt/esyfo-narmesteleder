package no.nav.syfo.narmesteleder.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory

const val SYKMELDING_NL_TOPIC = "teamsykmelding.syfo-narmesteleder-leesah"

class LeesahNLKafkaConsumer(
    private val kafkaConsumer: KafkaConsumer<String, String?>,
    private val jacksonMapper: ObjectMapper,
    private val handler: NlBehovLeesahHandler,
    private val scope: CoroutineScope,
) : KafkaListener {
    private lateinit var job: Job
    var commitOnAllErrors = false

    override fun listen() {
        logger.info("Starting leesah consumer")
        job = scope.launch(Dispatchers.IO + CoroutineName("leesah-consumer")) {

            kafkaConsumer.subscribe(listOf(SYKMELDING_NL_TOPIC))
            while (isActive) {
                try {
                    kafkaConsumer.poll(Duration.ofSeconds(POLL_DURATION_SECONDS)).let { records ->
                        if (records.count() > 1) {
                            error("MAX_POLL_RECORDS_CONFIG should not be greater than 1 for this consumer!")
                        }
                        val message = records
                            .firstOrNull()?.value()

                        message?.let {
                            val nlKafkaMessage =
                                jacksonMapper.readValue<NarmestelederLeesahKafkaMessage>(it)

                            logger.info("Processing NL message with id: ${nlKafkaMessage.narmesteLederId}")
                            handler.handleByLeesahStatus(nlKafkaMessage.toNlBehovWrite(), nlKafkaMessage.status)
                            kafkaConsumer.commitSync()
                        }
                    }
                } catch (_: WakeupException) {
                    logger.info("Waked Kafka consumer")
                } catch (e: Exception) {
                    logger.error(
                        "Error running kafka consumer. Waiting $DELAY_ON_ERROR_SECONDS seconds for retry.", e
                    )
                    kafkaConsumer.unsubscribe()
                    delay(DELAY_ON_ERROR_SECONDS.seconds)
                    kafkaConsumer.subscribe(listOf(SYKMELDING_NL_TOPIC))
                }
            }
            kafkaConsumer.close()
            logger.info("Exited Leesah consumer loop")
        }
    }

    override suspend fun stop() {
        if (!::job.isInitialized) error("Consumer not started!")

        logger.info("Preparing shutdown")
        logger.info("Stopping consuming topic $SYKMELDING_NL_TOPIC")

        job.cancel()
        kafkaConsumer.wakeup()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LeesahNLKafkaConsumer::class.java)
        private const val DELAY_ON_ERROR_SECONDS = 60L
        private const val POLL_DURATION_SECONDS = 1L
    }
}
