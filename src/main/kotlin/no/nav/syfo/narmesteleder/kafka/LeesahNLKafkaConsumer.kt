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
import no.nav.syfo.narmesteleder.service.NarmesteLederLeesahService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.slf4j.LoggerFactory

const val SYKMELDING_NL_TOPIC = "teamsykmelding.syfo-narmesteleder-leesah"

class LeesahNLKafkaConsumer(
    private val kafkaConsumer: KafkaConsumer<String, String>,
    private val jacksonMapper: ObjectMapper,
    private val nlLeesahService: NarmesteLederLeesahService,
    private val scope: CoroutineScope,
) : KafkaListener {
    private lateinit var job: Job
    private val processed = mutableMapOf<TopicPartition, Long>()

    override fun listen() {
        log.info("Starting leesah consumer")
        job = scope.launch(Dispatchers.IO + CoroutineName("leesah-consumer")) {

            while (job.isActive) {
                try {
                    kafkaConsumer.subscribe(listOf(SYKMELDING_NL_TOPIC))
                    start()
                } catch (e: Exception) {
                    log.error(
                        "Error running kafka consumer. Waiting $DELAY_ON_ERROR_SECONDS seconds for retry.",
                        e
                    )
                    commitProcessedSync()
                    kafkaConsumer.unsubscribe()
                    delay(DELAY_ON_ERROR_SECONDS.seconds)
                }
            }
            kafkaConsumer.close()
            log.info("Exited Leesah consumer loop")
        }
    }

    private suspend fun start() {
        while (true) {
            try {
                val messages = kafkaConsumer.suspendingPoll(POLL_DURATION_SECONDS.seconds)
                if (messages.isEmpty) continue

                messages.forEach { record: ConsumerRecord<String, String> ->
                    log.info("Received record with key: ${record.key()}")
                    processRecord(record)
                }
                commitProcessedSync()
            } catch (_: CancellationException) {
                log.debug("Received shutdown signal. Exiting polling loop.")
                break
            }
        }
    }

    private fun commitProcessedSync() {
        if (processed.isEmpty()) return

        val toCommit = processed.mapValues { (_, off) -> OffsetAndMetadata(off + 1) }
        if (toCommit.isNotEmpty()) kafkaConsumer.commitSync(toCommit)

        processed.clear()
        log.info("Commited offsets for partitions")
    }

    override suspend fun stop() {
        if (!::job.isInitialized) error("Consumer not started!")

        log.info("Preparing shutdown")
        log.info("Stopping consuming topic $SYKMELDING_NL_TOPIC")

        job.cancelAndJoin()
    }

    private suspend fun processRecord(record: ConsumerRecord<String, String>) {
        try {
            val nlKafkaMessage =
                jacksonMapper.readValue<NarmestelederLeesahKafkaMessage>(record.value())
            log.info("Processing NL message with id: ${nlKafkaMessage.narmesteLederId}")
            nlLeesahService.handleNarmesteLederLeesahMessage(nlKafkaMessage)
        } catch (e: JsonMappingException) {
            log.error(
                "Error while deserializing record with key ${record.key()} " +
                        "and offset ${record.offset()}. Will ack + continue to next message.",
                e
            )
        } finally {
            processed[TopicPartition(record.topic(), record.partition())] = record.offset()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LeesahNLKafkaConsumer::class.java)
        private const val DELAY_ON_ERROR_SECONDS = 60L
        private const val POLL_DURATION_SECONDS = 1L
    }
}
