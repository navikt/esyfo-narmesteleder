package no.nav.syfo.application.leaderelection

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.environment.isLocalEnv
import java.net.InetAddress

/**
 * Leader election implementation that queries endpoint in a sidecar
 * to determine if the current instance/pod is the leader or not
 */
class LeaderElection(
    private val httpClient: HttpClient,
    private val electorPath: String,
) {
    suspend fun isLeader(): Boolean {
        val hostname: String = withContext(Dispatchers.IO) { InetAddress.getLocalHost() }.hostName

        return if (isLocalEnv()) {
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
