package no.nav.syfo.narmesteleder.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.RuntimeJsonMappingException
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import no.nav.syfo.narmesteleder.service.NarmesteLederLeesahService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
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
    private val running = AtomicBoolean(false)

    override fun listen() {
        log.info("Starting leesah consumer")
        running.set(true)
        job = scope.launch(Dispatchers.IO + CoroutineName("leesah-consumer")) {

            while (isActive && running.get()) {
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
        while (running.get()) {
            try {
                val messages = kafkaConsumer.poll(Duration.ofSeconds(POLL_DURATION_SECONDS))
                if (messages.isEmpty) continue

                messages.forEach { record: ConsumerRecord<String, String> ->
                    log.info("Received record with key: ${record.key()}")
                    processRecord(record)
                }
                commitProcessedSync()
            } catch (_: WakeupException) {
                log.debug("Recieved shutdown signal. Exiting polling loop.")
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

        running.set(false)
        log.info("Preparing shutdown")
        log.info("Stopping consuming topic $SYKMELDING_NL_TOPIC")

        kafkaConsumer.wakeup()
        job.cancelAndJoin()
    }

    private suspend fun processRecord(record: ConsumerRecord<String, String>) {
        try {
            val nlKafkaMessage =
                jacksonMapper.readValue<NarmestelederLeesahKafkaMessage>(record.value())
            log.info("Processing NL message with id: ${nlKafkaMessage.narmesteLederId}")
            nlLeesahService.handleNarmesteLederLeesahMessage(nlKafkaMessage)
        } catch (e: RuntimeJsonMappingException) {
            log.error("Error processing NL message with key ${record.key()} and offset ${record.offset()}", e)
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
