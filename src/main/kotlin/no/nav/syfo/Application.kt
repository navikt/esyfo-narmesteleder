package no.nav.syfo

import io.ktor.server.application.Application
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.concurrent.TimeUnit
import no.nav.syfo.application.api.configureRouting
import no.nav.syfo.plugins.configureDependencies
import no.nav.syfo.plugins.configureLifecycleHooks
import no.nav.syfo.plugins.configureRunningTasks
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

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(10, 10, TimeUnit.SECONDS)
        }
    )

    server.start(true)
}

fun Application.module() {
    configureDependencies()
    configureLifecycleHooks(get())
    configureRouting()
    configureRunningTasks()
}
