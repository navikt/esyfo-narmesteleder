package no.nav.syfo

import io.ktor.server.application.Application
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import no.nav.syfo.application.api.configureRouting
import no.nav.syfo.application.environment.isLocalEnv
import no.nav.syfo.application.metric.bindJvmAndProcessMetrics
import no.nav.syfo.narmestelederstatistikk.narmestelederstatistikkModule
import no.nav.syfo.plugins.applicationModules
import no.nav.syfo.plugins.configureBackgroundTasks
import no.nav.syfo.plugins.configureKafkaConsumers
import no.nav.syfo.plugins.configureLeaderMonitoring
import no.nav.syfo.plugins.configureLifecycleHooks
import no.nav.syfo.plugins.platformModule
import org.koin.ktor.ext.get

fun main() {
    val server = embeddedServer(
        Netty,
        configure = {
            connector {
                port = 8080
            }
            connectionGroupSize = 8
            workerGroupSize = 8
            callGroupSize = 16
        },
        module = Application::module
    )

    server.start(true)
}

fun Application.module() {
    platformModule(applicationModules(isLocalEnv()))
    bindJvmAndProcessMetrics()
    configureLifecycleHooks(get())
    configureLeaderMonitoring(get(), get())
    configureRouting()
    narmestelederstatistikkModule()
    configureKafkaConsumers()
    configureBackgroundTasks()
}
