package no.nav.syfo.platform.scheduling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Leader election implementation that queries endpoint in a sidecar
 * to determine if the current instance/pod is the leader or not
 */
class LeaderElection(
    private val httpClient: HttpClient,
    private val electorPath: String,
    private val isLocal: Boolean,
) {
    suspend fun isLeader(): Boolean {
        val hostname: String = withContext(Dispatchers.IO) { InetAddress.getLocalHost() }.hostName

        return if (isLocal) {
            true
        } else {
            val leader = httpClient.get(getHttpPath(electorPath)).body<Leader>()
            leader.name == hostname
        }
    }

    private fun getHttpPath(url: String): String = when (url.startsWith("http://")) {
        true -> url
        else -> "http://$url"
    }

    private data class Leader(val name: String)
}
