package no.nav.syfo.platform.scheduling

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** Tells whether this pod is the elected leader. */
interface LeaderElection {
    suspend fun isLeader(): Boolean
}

/**
 * Leader election implementation that queries endpoint in a sidecar
 * to determine if the current instance/pod is the leader or not
 */
class HttpLeaderElection(
    private val httpClient: HttpClient,
    private val electorPath: String,
) : LeaderElection {
    override suspend fun isLeader(): Boolean {
        val hostname: String = withContext(Dispatchers.IO) { InetAddress.getLocalHost() }.hostName
        val leader = httpClient.get(getHttpPath(electorPath)).body<Leader>()
        return leader.name == hostname
    }

    private fun getHttpPath(url: String): String = when (url.startsWith("http://")) {
        true -> url
        else -> "http://$url"
    }

    private data class Leader(val name: String)
}
