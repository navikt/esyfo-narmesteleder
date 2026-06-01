package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch
import no.nav.syfo.application.events.LeaderChange
import no.nav.syfo.application.events.LeaderChangeEvent
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
import no.nav.syfo.util.logger

fun Application.configureLeaderMonitoring(leaderChangeSSEListener: LeaderChangeSSEListener) {
    val log = logger()

    monitor.subscribe(ServerReady) {
        log.info("Starting leader monitoring")
        val job = launch {
            log.info("Before listenForLeaderChanges - Application.configureLeaderMonitoring")
            launch { leaderChangeSSEListener.listenForLeaderChanges() }

            var wasLeader = false
            leaderChangeSSEListener.isLeader.collectIndexed { index, isLeader ->
                val event = deriveLeaderChange(wasLeader, isLeader)
                wasLeader = isLeader

                // Skip the initial default value (false) from MutableStateFlow
                if (index == 0 && event is LeaderChange.Unaffected) return@collectIndexed

                log.info("Leader change event: {}", event::class.simpleName)
                monitor.raise(LeaderChangeEvent, event)
            }
        }

        monitor.subscribe(ApplicationStopPreparing) {
            job.cancel()
        }
    }
}

internal fun deriveLeaderChange(wasLeader: Boolean, isLeader: Boolean): LeaderChange = when {
    !wasLeader && isLeader -> LeaderChange.Promoted
    wasLeader && !isLeader -> LeaderChange.Demoted
    else -> LeaderChange.Unaffected
}
