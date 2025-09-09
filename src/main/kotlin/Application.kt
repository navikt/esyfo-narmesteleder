package no.nav.syfo

import io.ktor.server.application.Application
import no.nav.syfo.no.nav.syfo.plugins.configureDependencies
import no.nav.syfo.no.nav.syfo.plugins.configureLifecycleHooks
import org.koin.ktor.ext.get

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureDependencies()
    configureLifecycleHooks(get())
    configureRouting(get(), get())
}
