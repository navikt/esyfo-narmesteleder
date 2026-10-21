package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import no.nav.syfo.bootstrap.Environment
import no.nav.syfo.bootstrap.LocalEnvironment
import no.nav.syfo.bootstrap.NaisEnvironment
import no.nav.syfo.platform.observability.ApplicationState
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.time.Clock

fun Application.configureDependencies(koinModules: List<Module>) {
    install(Koin) {
        slf4jLogger()
        modules(koinModules)
    }
}

fun applicationModules(isLocalEnv: Boolean): List<Module> = listOf(
    coreModule(isLocalEnv),
    databaseModule(),
    httpClientsModule(isLocalEnv),
    cacheModule(),
    kafkaProducersModule(),
    leaderElectionModule(isLocalEnv),
    legacyServicesModule(),
    tasksModule(),
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
