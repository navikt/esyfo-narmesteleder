package no.nav.syfo.application.leaderelection

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import no.nav.syfo.application.environment.isLocalEnv
import no.nav.syfo.util.logger
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Leader election implementation using Server-Sent Events (SSE).
 * Listens to the NAIS leader elector sidecar SSE endpoint for leader changes.
 *
 * An elected leader pod retains its status during the entire lifecycle
 * See: https://docs.nais.io/services/leader-election/
 */
class LeaderChangeSSEListener(
    private val sseHttpClient: HttpClient,
    private val electorSseUrl: String,
) {
    private val log = logger()

    private val jsonMapper = jsonMapper {
        addModule(kotlinModule())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val _isLeader = MutableStateFlow(false)
    private val isListening = AtomicBoolean(false)
    val isLeader: StateFlow<Boolean> = _isLeader.asStateFlow()

    private var hostname: String? = null

    /**
     * Starts listening for leader change events from the NAIS elector sidecar.
     * This is a suspending function that will run indefinitely, collecting SSE events.
     * Should be launched in a coroutine scope that manages the lifecycle.
     */
    suspend fun listenForLeaderChanges() = coroutineScope {
        if (!isListening.compareAndSet(false, true)) {
            log.warn("Already listening for leader changes, ignoring duplicate call")
            return@coroutineScope
        }

        if (isLocalEnv()) {
            log.info("Running in local environment, setting isLeader to true")
            _isLeader.value = true
            return@coroutineScope
        }

        hostname = withContext(Dispatchers.IO) { InetAddress.getLocalHost().hostName }

        while (isActive) {
            runCatching {
                sseHttpClient.sse(electorSseUrl) {
                    log.info("Connected to leader election listener for hostname: $hostname")
                    incoming.collect { event ->
                        val data = event.data
                        if (data != null) {
                            try {
                                val leaderResponse = jsonMapper.readValue<LeaderElectorResponse>(data)
                                val isNowLeader = leaderResponse.name == hostname
                                _isLeader.value = isNowLeader

                                log.info(
                                    "Leader status changed: isLeader=$isNowLeader " +
                                        "(current leader: ${leaderResponse.name}, this pod: $hostname)"
                                )
                            } catch (e: Exception) {
                                log.warn("Error parsing leader elector response: $data", e)
                            }
                        }
                    }
                }
            }.onFailure {
                if (it is CancellationException) break

                log.warn("Could not connect to leader election listener for hostname: $hostname. Retrying in ${SSE_CLIENT_RETRY_DELAY_MS.milliseconds.inWholeSeconds} seconds...", it)
                delay(SSE_CLIENT_RETRY_DELAY_MS.milliseconds)
            }
        }
    }

    companion object {
        private const val SSE_CLIENT_RETRY_DELAY_MS = 5000L
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LeaderElectorResponse(
    val name: String,
    val last_update: String,
)
