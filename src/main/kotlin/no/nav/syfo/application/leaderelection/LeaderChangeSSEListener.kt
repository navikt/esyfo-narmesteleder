package no.nav.syfo.application.leaderelection

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.url
import io.ktor.server.util.url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import no.nav.syfo.application.environment.isLocalEnv
import no.nav.syfo.util.logger
import java.net.InetAddress

/**
 * Leader election implementation using Server-Sent Events (SSE).
 * Listens to the NAIS leader elector sidecar SSE endpoint for leader changes.
 *
 * See: https://docs.nais.io/services/leader-election/
 */
class LeaderChangeSSEListener(
    private val sseHttpClient: HttpClient,
    private val electorPath: String,
) {
    private val log = logger()

    private val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val _isLeader = MutableStateFlow(false)
    val isLeader: StateFlow<Boolean> = _isLeader.asStateFlow()

    private var hostname: String? = null

    /**
     * Starts listening for leader change events from the NAIS elector sidecar.
     * This is a suspending function that will run indefinitely, collecting SSE events.
     * Should be launched in a coroutine scope that manages the lifecycle.
     */
    suspend fun listenForLeaderChanges() = coroutineScope {
        if (isLocalEnv()) {
            log.info("Running in local environment, setting isLeader to true")
            _isLeader.value = true
            return@coroutineScope
        }

        hostname = withContext(Dispatchers.IO) { InetAddress.getLocalHost().hostName }
        log.info("Starting SSE leader election listener for hostname: $hostname")

        val sseUrl = getHttpPath(electorPath)
        log.info("Connecting to leader elector SSE endpoint: $sseUrl")

        sseHttpClient.sse(sseUrl) {
            incoming.collect { event ->
                val data = event.data
                if (data != null) {
                    try {
                        val leaderResponse = objectMapper.readValue<LeaderElectorResponse>(data)
                        val wasLeader = _isLeader.value
                        val isNowLeader = leaderResponse.name == hostname
                        _isLeader.value = isNowLeader

                        if (wasLeader != isNowLeader) {
                            log.info(
                                "Leader status changed: isLeader=$isNowLeader " +
                                    "(current leader: ${leaderResponse.name}, this pod: $hostname)"
                            )
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to parse leader election SSE event: $data", e)
                    }
                }
            }
        }
    }

    /**
     * Checks if this instance is currently the leader.
     * Uses the cached state from SSE events.
     */
    fun isLeader(): Boolean = _isLeader.value

    private fun getHttpPath(url: String): String = when (url.startsWith("http://")) {
        true -> url
        else -> "http://$url"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LeaderElectorResponse(
    val name: String,
    val last_update: String,
)
