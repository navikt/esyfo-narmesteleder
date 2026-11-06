package no.nav.syfo

import io.ktor.server.application.Application
import no.nav.syfo.bootstrap.LocalEnvironment
import no.nav.syfo.bootstrap.bootstrap
import no.nav.syfo.bootstrap.localExternalClientsModule
import no.nav.syfo.bootstrap.localLeaderElectionModule

/** Runs the application locally with fake external clients. Started by `./gradlew runLocal`. */
fun main() {
    startServer(Application::localModule)
}

fun Application.localModule() = bootstrap(
    environment = LocalEnvironment(),
    externalClients = localExternalClientsModule(),
    leaderElection = localLeaderElectionModule(),
)
