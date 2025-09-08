package no.nav.syfo

import io.ktor.server.application.Application
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.no.nav.syfo.plugins.configureLifecycleHooks

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val applicationState = ApplicationState()
    configureLifecycleHooks(applicationState)
    configureRouting(applicationState)
}
