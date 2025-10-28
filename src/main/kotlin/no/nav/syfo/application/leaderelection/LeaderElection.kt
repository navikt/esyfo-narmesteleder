package no.nav.syfo.application.leaderelection

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.application.isLocalEnv
import no.nav.syfo.util.logger

class LeaderElection(
    private val httpClient: HttpClient,
    private val electorPath: String,
) {
    private val log = logger()

    suspend fun isLeader(): Boolean {
        val hostname: String = withContext(Dispatchers.IO) { InetAddress.getLocalHost() }.hostName

        try {
            val isLeader = if (isLocalEnv()) true
            else {

                val leader = httpClient.get(getHttpPath(electorPath)).body<Leader>()
                leader.name == hostname
            }
            return isLeader
        } catch (e: Exception) {
            log.error("Kall mot elector feiler", e)
            throw e
        }
    }

    private fun getHttpPath(url: String): String =
        when (url.startsWith("http://")) {
            true -> url
            else -> "http://$url"
        }

    private data class Leader(val name: String)
}
