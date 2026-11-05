package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.runBlocking
import no.nav.syfo.narmestelederbehov.application.DialogReconciliationSettings
import no.nav.syfo.narmestelederbehov.application.ReconcileNarmestelederbehovDialogUseCase
import no.nav.syfo.narmestelederbehov.infrastructure.MicrometerDialogReconciliationMetrics
import no.nav.syfo.platform.scheduling.BackgroundLoop
import org.koin.ktor.ext.inject

internal fun Application.configureDialogReconciliation(other: OtherEnvironmentProperties) {
    val metrics by inject<MicrometerDialogReconciliationMetrics>()
    metrics.enabled(other.dialogReconciliationEnabled)
    if (!other.dialogReconciliationEnabled) return
    val settings by inject<DialogReconciliationSettings>()
    val reconcile by inject<ReconcileNarmestelederbehovDialogUseCase>()
    val loop = BackgroundLoop(name = "dialog-reconciliation", interval = settings.interval) {
        metrics.refresh()
        reconcile.execute()
    }
    monitor.subscribe(ServerReady) { loop.start(this) }
    monitor.subscribe(ApplicationStopPreparing) { runBlocking { loop.stop() } }
}
