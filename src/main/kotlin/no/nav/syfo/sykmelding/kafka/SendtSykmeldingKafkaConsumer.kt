package no.nav.syfo.sykmelding.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.sykmelding.model.SendtSykmeldingKafkaMessage
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

const val SENDT_SYKMELDING_TOPIC = "teamsykmelding.syfo-sendt-sykmelding"

class SendtSykmeldingKafkaConsumer(
    private val handler: SendtSykmeldingHandler,
    private val jacksonMapper: ObjectMapper,
    private val kafkaConsumer: KafkaConsumer<String, String>,
    private val scope: CoroutineScope,
) : KafkaListener {
    private lateinit var job: Job

    override fun listen() {
        logger.info("Starting $SENDT_SYKMELDING_TOPIC consumer")
        job = scope.launch(Dispatchers.IO + CoroutineName("sendt-sykmelding-consumer")) {
            kafkaConsumer.subscribe(listOf(SENDT_SYKMELDING_TOPIC))
            while (isActive) {
                try {
                    kafkaConsumer.poll(Duration.ofSeconds(POLL_DURATION_SECONDS))
                        .forEach { record: ConsumerRecord<String, String?> ->
                            logger.info("Received record with key: ${record.key()}")
                            val sykmeldingMessage = record.value()
                            val sykmeldingId = record.key()
                            val isTombstone = sykmeldingMessage == null

                            if (isTombstone) {
                                logger.info("Received tombstone for sykmeldingId: $sykmeldingId.")
                                handler.handleTombstone(sykmeldingId)
                            } else {
                                val sendtSykmeldingKafkaMessage =
                                    jacksonMapper.readValue<SendtSykmeldingKafkaMessage>(sykmeldingMessage)
                                handler.handleSendtSykmelding(sendtSykmeldingKafkaMessage)
                            }
                            kafkaConsumer.commitSync()
                        }
                } catch (_: WakeupException) {
                    logger.info("Waked Kafka consumer")
                } catch (e: Exception) {
                    logger.error(
                        "Error running kafka consumer. Waiting $DELAY_ON_ERROR_SECONDS seconds for retry.",
                        e
                    )
                    kafkaConsumer.unsubscribe()
                    delay(DELAY_ON_ERROR_SECONDS.seconds)
                    kafkaConsumer.subscribe(listOf(SENDT_SYKMELDING_TOPIC))
                }
            }
            kafkaConsumer.close()
            logger.info("Exited $SENDT_SYKMELDING_TOPIC consumer loop")
        }
    }

    override suspend fun stop() {
        if (!::job.isInitialized) error("$SENDT_SYKMELDING_TOPIC consumer not started!")

        logger.info("Preparing shutdown")
        logger.info("Stopping consuming topic $SENDT_SYKMELDING_TOPIC")

        job.cancel()
        kafkaConsumer.wakeup()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SendtSykmeldingKafkaConsumer::class.java)
        private const val DELAY_ON_ERROR_SECONDS = 60L
        private const val POLL_DURATION_SECONDS = 1L
    }
}
