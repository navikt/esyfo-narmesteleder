package no.nav.syfo

import io.ktor.server.application.Application
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import no.nav.syfo.bootstrap.NaisEnvironment
import no.nav.syfo.bootstrap.bootstrap

fun main() {
    startServer(Application::module)
}

fun Application.module() = bootstrap(NaisEnvironment())

internal fun startServer(module: Application.() -> Unit) {
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
        module = module,
    )

    server.start(true)
}
