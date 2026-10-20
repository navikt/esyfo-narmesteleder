package no.nav.syfo.sykmelding

import io.ktor.server.application.Application
import org.koin.ktor.plugin.koinModules

fun Application.sykmeldingModule() {
    koinModules(sykmeldingDependencies())
}
