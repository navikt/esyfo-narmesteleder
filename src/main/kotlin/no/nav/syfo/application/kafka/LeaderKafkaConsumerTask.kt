package no.nav.syfo.application.kafka

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import no.nav.syfo.util.logger
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class LeaderKafkaConsumerTask<K, V>(
    name: String,
    protected val kafkaConsumer: KafkaConsumer<K, V>,
    private val topics: List<String>,
    private val pollDuration: Duration = Duration.ofSeconds(POLL_DURATION_SECONDS),
    private val retryDelay: kotlin.time.Duration = DEFAULT_RETRY_DELAY,
) {
    private val logger = logger(name)
    private val taskName = name

    abstract suspend fun processRecords(records: ConsumerRecords<K, V>)

    suspend fun runTask() = coroutineScope {
        val wakeupHandle = coroutineContext.job.invokeOnCompletion { kafkaConsumer.wakeup() }
        kafkaConsumer.subscribe(topics)
        try {
            while (isActive) {
                try {
                    val records = kafkaConsumer.poll(pollDuration)
                    if (!records.isEmpty) {
                        processRecords(records)
                    }
                } catch (_: WakeupException) {
                    break
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    handlePollError(ex)
                }
            }
        } catch (_: CancellationException) {
            logger.info("$taskName stopped gracefully")
        } finally {
            wakeupHandle.dispose()
            runCatching { kafkaConsumer.close() }.onFailure {
                if (it !is WakeupException) logger.warn("Error closing consumer for $taskName", it)
            }
            logger.info("$taskName consumer closed")
        }
    }

    protected open suspend fun handlePollError(ex: Exception) {
        logger.error("Error in $taskName. Retrying in $retryDelay", ex)
        kafkaConsumer.unsubscribe()
        delay(retryDelay)
        kafkaConsumer.subscribe(topics)
    }

    companion object {
        private const val POLL_DURATION_SECONDS = 1L
        private val DEFAULT_RETRY_DELAY = 60.seconds
    }
}
