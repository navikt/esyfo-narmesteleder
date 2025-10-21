package no.nav.syfo.narmesteleder.kafka

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.application.kafka.suspendingPoll
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.kafka.NarmesteLederLeesahHandler
import no.nav.syfo.narmesteleder.service.NarmesteLederService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory

const val SYKMELDING_NL_TOPIC = "teamsykmelding.syfo-narmesteleder-leesah"

class LeesahNLKafkaConsumer(
    private val kafkaConsumer: KafkaConsumer<String, String>,
    private val jacksonMapper: ObjectMapper,
    private val handler: NarmesteLederLeesahHandler,
    private val scope: CoroutineScope,
) : KafkaListener {
    private lateinit var job: Job
    private val processed = mutableMapOf<TopicPartition, Long>()

    override fun listen() {
        logger.info("Starting leesah consumer")
        job = scope.launch(Dispatchers.IO + CoroutineName("leesah-consumer")) {

            while (job.isActive) {
                try {
                    kafkaConsumer.subscribe(listOf(SYKMELDING_NL_TOPIC))
                    start()
                } catch (e: Exception) {
                    logger.error(
                        "Error running kafka consumer. Waiting $DELAY_ON_ERROR_SECONDS seconds for retry.",
                        e
                    )
                    commitProcessedSync()
                    kafkaConsumer.unsubscribe()
                    delay(DELAY_ON_ERROR_SECONDS.seconds)
                }
            }
            kafkaConsumer.close()
            logger.info("Exited Leesah consumer loop")
        }
    }

    private suspend fun start() {
        while (true) {
            try {
                kafkaConsumer.suspendingPoll(POLL_DURATION_SECONDS.seconds)
                    .forEach { record: ConsumerRecord<String, String> ->
                        logger.info("Received record with key: ${record.key()}")
                        processRecord(record)
                    }
                commitProcessedSync()
            } catch (_: CancellationException) {
                logger.debug("Received shutdown signal. Exiting polling loop.")
                break
            }
        }
    }

    private fun commitProcessedSync() {
        if (processed.isEmpty()) return

        val toCommit = processed.mapValues { (_, off) -> OffsetAndMetadata(off + 1) }
        if (toCommit.isNotEmpty()) kafkaConsumer.commitSync(toCommit)

        processed.clear()
        logger.info("Commited offsets for partitions")
    }

    override suspend fun stop() {
        if (!::job.isInitialized) error("Consumer not started!")

        logger.info("Preparing shutdown")
        logger.info("Stopping consuming topic $SYKMELDING_NL_TOPIC")

        job.cancelAndJoin()
    }

    private suspend fun processRecord(record: ConsumerRecord<String, String>) {
        try {
            val nlKafkaMessage =
                jacksonMapper.readValue<NarmestelederLeesahKafkaMessage>(record.value())
            logger.info("Processing NL message with id: ${nlKafkaMessage.narmesteLederId}")
            handler.handleByLeesahStatus(nlKafkaMessage.toNlBehovWrite(), nlKafkaMessage.status)
        } catch (e: JsonMappingException) {
            logger.error(
                "Error while deserializing record with key ${record.key()} " +
                        "and offset ${record.offset()}. Will ack + continue to next message.",
                e
            )
        } finally {
            processed[TopicPartition(record.topic(), record.partition())] = record.offset()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LeesahNLKafkaConsumer::class.java)
        private const val DELAY_ON_ERROR_SECONDS = 60L
        private const val POLL_DURATION_SECONDS = 1L
    }
}
