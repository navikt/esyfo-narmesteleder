package no.nav.syfo.narmesteleder.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nav.syfo.application.kafka.KafkaListener
import org.slf4j.LoggerFactory

class LeaderControlledKafkaConsumer(
    private val consumerName: String,
    private val consumer: KafkaListener,
    private val enabled: Boolean = true,
    private val closeable: AutoCloseable? = consumer as? AutoCloseable,
) {
    private val lifecycleMutex = Mutex()
    private var running = false

    suspend fun onLeaderChange(isLeader: Boolean) {
        if (!enabled) {
            return
        }

        lifecycleMutex.withLock {
            when {
                isLeader && !running -> {
                    logger.info("This instance is now the leader. Starting {}.", consumerName)
                    consumer.listen()
                    running = true
                }

                !isLeader && running -> {
                    logger.info("This instance lost leadership. Stopping {}.", consumerName)
                    consumer.stop()
                    running = false
                }
            }
        }
    }

    suspend fun stop() {
        lifecycleMutex.withLock {
            if (running) {
                consumer.stop()
                running = false
            }
        }
    }

    fun close() {
        closeable?.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LeaderControlledKafkaConsumer::class.java)
    }
}
