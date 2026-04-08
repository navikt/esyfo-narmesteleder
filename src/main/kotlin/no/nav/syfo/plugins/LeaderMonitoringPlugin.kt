package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.launch
import no.nav.syfo.application.events.LeaderChange
import no.nav.syfo.application.events.LeaderChangeEvent
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
import no.nav.syfo.util.logger

fun Application.configureLeaderMonitoring(leaderChangeSSEListener: LeaderChangeSSEListener) {
    monitor.subscribe(ServerReady) {
        logger().info("Starting leader monitoring")
        val job = launch {
            val monitor = this@configureLeaderMonitoring.monitor
            leaderChangeSSEListener.isLeader.collect { isLeader ->
                if (isLeader) {
                    logger().info("This instance is now the leader")
                    monitor.raise(LeaderChangeEvent, LeaderChange.ElectedLeader)
                } else {
                    monitor.raise(LeaderChangeEvent, LeaderChange.NotLeader)
                }
            }
        }

        monitor.subscribe(ApplicationStopPreparing) {
            job.cancel()
        }
    }
}
