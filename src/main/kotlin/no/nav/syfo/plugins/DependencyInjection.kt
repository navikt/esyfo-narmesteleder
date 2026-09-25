package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.environment.LocalEnvironment
import no.nav.syfo.application.environment.NaisEnvironment
import no.nav.syfo.application.environment.isLocalEnv
import no.nav.syfo.narmestelederbehov.narmestelederbehovModule
import no.nav.syfo.narmestelederrelasjon.narmestelederrelasjonModule
import no.nav.syfo.organisasjonstilgang.organisasjonstilgangModule
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.time.Clock

fun Application.configureDependencies() {
    install(Koin) {
        slf4jLogger()
        modules(applicationModules(isLocalEnv()))
    }
}

fun applicationModules(isLocalEnv: Boolean): List<Module> = listOf(
    coreModule(isLocalEnv),
    databaseModule(),
    httpClientsModule(isLocalEnv),
    cacheModule(),
    kafkaProducersModule(),
    leaderElectionModule(isLocalEnv),
    legacyRepositoriesModule(),
    legacyServicesModule(),
    tasksModule(),
    narmestelederbehovModule(),
    narmestelederrelasjonModule(),
    organisasjonstilgangModule(),
)

private fun coreModule(isLocalEnv: Boolean) = module {
    single { ApplicationState() }
    single<Environment> {
        if (isLocalEnv) {
            LocalEnvironment()
        } else {
            NaisEnvironment()
        }
    }
    single { Clock.systemDefaultZone() }
}

internal fun Scope.env() = get<Environment>()
