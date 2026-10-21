package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.install
import no.nav.syfo.platform.observability.ApplicationState
import org.koin.core.module.Module
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

internal fun applicationModules(environment: Environment, isLocalEnv: Boolean): List<Module> = listOf(
    coreModule(),
    databaseModule(environment.database),
    httpClientsModule(environment.clientProperties, environment.texas, isLocalEnv),
    cacheModule(environment.valkeyEnvironment),
    kafkaProducersModule(environment.kafka),
    leaderElectionModule(environment.otherProperties, isLocalEnv),
    tasksModule(environment.otherProperties),
)

private fun coreModule() = module {
    single { ApplicationState() }
    single { Clock.systemDefaultZone() }
}
