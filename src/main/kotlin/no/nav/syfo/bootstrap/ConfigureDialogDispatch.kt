package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.runBlocking
import no.nav.syfo.narmestelederbehov.DialogDispatchLoopSettings
import no.nav.syfo.narmestelederbehov.application.DispatchNarmestelederbehovDialogUseCase
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerDialogDispatchMetrics
import no.nav.syfo.platform.scheduling.BackgroundLoop
import org.koin.ktor.ext.inject

internal fun Application.configureDialogDispatch(settings: DialogDispatchLoopSettings) {
    val metrics by inject<MicrometerDialogDispatchMetrics>()
    metrics.enabled(settings.enabled)
    if (!settings.enabled) return
    val dispatch by inject<DispatchNarmestelederbehovDialogUseCase>()
    val loop = BackgroundLoop(name = "dialog-dispatch", interval = settings.interval) {
        metrics.refresh()
        dispatch.execute()
    }
    monitor.subscribe(ServerReady) { loop.start(this) }
    monitor.subscribe(ApplicationStopPreparing) { runBlocking { loop.stop() } }
}
