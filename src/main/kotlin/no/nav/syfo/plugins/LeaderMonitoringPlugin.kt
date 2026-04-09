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
    val logger = logger("no.nav.syfo.LeaderMonitoringPlugin")

    monitor.subscribe(ServerReady) {
        logger.info("Starting leader monitoring")
        val listenerJob = launch {
            leaderChangeSSEListener.listenForLeaderChanges()
        }

        val collectorJob = launch {
            val monitor = this@configureLeaderMonitoring.monitor
            var electedLeader = false

            leaderChangeSSEListener.isLeader.collect { isLeader ->
                if (isLeader && !electedLeader) {
                    logger.info("Instance promoted to leader")
                    monitor.raise(LeaderChangeEvent, LeaderChange.Promoted)
                    electedLeader = true
                } else if (!isLeader && electedLeader) {
                    logger.info("This instance demoted from leader")
                    monitor.raise(LeaderChangeEvent, LeaderChange.Demoted)
                    electedLeader = false
                } else {
                    monitor.raise(LeaderChangeEvent, LeaderChange.Unaffected)
                }
            }
        }

        monitor.subscribe(ApplicationStopPreparing) {
            listenerJob.cancel()
            collectorJob.cancel()
        }
    }
}
