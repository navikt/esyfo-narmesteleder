package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch
import no.nav.syfo.application.events.LeaderChange
import no.nav.syfo.application.events.LeaderChangeEvent
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
import no.nav.syfo.application.leaderelection.LeaderElection
import no.nav.syfo.util.logger

fun Application.configureLeaderMonitoring(
    leaderChangeSSEListener: LeaderChangeSSEListener,
    leaderElection: LeaderElection,
) {
    val log = logger()

    monitor.subscribe(ServerReady) {
        log.info("Starting leader monitoring")
        val job = launch {
            var wasLeader = false
            launch(start = CoroutineStart.UNDISPATCHED) {
                leaderChangeSSEListener.isLeader.collectIndexed { index, isLeader ->
                    val event = deriveLeaderChangeEvent(index, wasLeader, isLeader)
                    wasLeader = isLeader

                    if (event == null) return@collectIndexed

                    log.info("Leader change event: {}", event::class.simpleName)
                    monitor.raise(LeaderChangeEvent, event)
                }
            }

            runCatching {
                leaderElection.isLeader()
            }.onSuccess { initialIsLeader ->
                leaderChangeSSEListener.initializeLeaderState(initialIsLeader)
                log.info("Initialized leader state from simple API: isLeader={}", initialIsLeader)
            }.onFailure { exception ->
                log.error(
                    "Failed to initialize leader state from simple API; continuing leader monitoring with SSE listener",
                    exception
                )
            }

            log.info("Before listenForLeaderChanges - Application.configureLeaderMonitoring")
            launch { leaderChangeSSEListener.listenForLeaderChanges() }
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

internal fun deriveLeaderChangeEvent(index: Int, wasLeader: Boolean, isLeader: Boolean): LeaderChange? {
    val event = deriveLeaderChange(wasLeader, isLeader)

    return if (index == 0 && event is LeaderChange.Unaffected) {
        null
    } else {
        event
    }
}
