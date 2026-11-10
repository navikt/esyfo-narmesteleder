package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.launch
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import no.nav.syfo.platform.observability.applicationEvent
import no.nav.syfo.platform.observability.logEvent
import no.nav.syfo.platform.observability.logger
import no.nav.syfo.platform.observability.rethrowCancellation
import no.nav.syfo.platform.scheduling.LeaderChange
import no.nav.syfo.platform.scheduling.LeaderChangeEvent
import no.nav.syfo.platform.scheduling.LeaderChangeListener
import no.nav.syfo.platform.scheduling.LeaderElection
import org.slf4j.event.Level
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

private val leaderInitializationFailed = applicationEvent<Unit>(
    name = "leader_initialization_failed",
    level = Level.WARN,
    message = "Initial leader lookup failed; continuing through the SSE listener",
    upstream = "elector",
)

const val LEADER_STATUS_METRIC = "${METRICS_NS}_leader_status"

private val leaderStatusStates = Collections.synchronizedMap(
    WeakHashMap<MeterRegistry, AtomicReference<StateFlow<Boolean>?>>()
)

fun Application.configureLeaderMonitoring(
    leaderChangeListener: LeaderChangeListener,
    leaderElection: LeaderElection,
) {
    val log = logger()

    registerLeaderStatusGauge(leaderChangeListener)
    monitor.subscribe(ApplicationStopPreparing) {
        clearLeaderStatusGauge(leaderChangeListener)
    }

    monitor.subscribe(ServerReady) {
        log.info("Starting leader monitoring")
        val job = launch {
            var wasLeader = false
            launch(start = CoroutineStart.UNDISPATCHED) {
                leaderChangeListener.isLeader.collectIndexed { index, isLeader ->
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
                leaderChangeListener.initializeLeaderState(initialIsLeader)
                log.info("Initialized leader state from simple API: isLeader={}", initialIsLeader)
            }.onFailure { exception ->
                exception.rethrowCancellation()
                log.logEvent(leaderInitializationFailed, Unit, cause = exception)
            }

            log.info("Before listenForLeaderChanges - Application.configureLeaderMonitoring")
            launch { leaderChangeListener.listenForLeaderChanges() }
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

internal fun registerLeaderStatusGauge(
    leaderChangeListener: LeaderChangeListener,
    registry: MeterRegistry = METRICS_REGISTRY,
): Gauge {
    val leaderStatusState = leaderStatusState(registry)
    leaderStatusState.set(leaderChangeListener.isLeader)

    return Gauge.builder(LEADER_STATUS_METRIC, leaderStatusState) { state ->
        if (state.get()?.value == true) 1.0 else 0.0
    }
        .description("Whether this pod is currently the elected leader (1) or not (0)")
        .strongReference(true)
        .register(registry)
}

internal fun clearLeaderStatusGauge(
    leaderChangeListener: LeaderChangeListener,
    registry: MeterRegistry = METRICS_REGISTRY,
) {
    leaderStatusState(registry).compareAndSet(leaderChangeListener.isLeader, null)
}

private fun leaderStatusState(registry: MeterRegistry): AtomicReference<StateFlow<Boolean>?> = synchronized(leaderStatusStates) {
    leaderStatusStates.getOrPut(registry) { AtomicReference() }
}
