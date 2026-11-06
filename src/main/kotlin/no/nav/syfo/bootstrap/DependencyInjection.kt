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

/** [externalClients] and [leaderElection] replace the real modules when given. */
internal fun applicationModules(
    environment: Environment,
    externalClients: Module? = null,
    leaderElection: Module? = null,
): List<Module> = listOf(
    coreModule(),
    databaseModule(environment.database),
    httpClientsModule(settings = environment.httpClientsSettings(), texas = environment.texas),
    externalClients ?: externalClientsModule(environment.httpClientsSettings()),
    cacheModule(environment.valkeyEnvironment),
    kafkaProducersModule(environment.kafka),
    leaderElection ?: leaderElectionModule(environment.leaderElectionSettings),
    tasksModule(
        narmestelederbehovExpirySettings = environment.narmestelederbehovExpirySettings,
        maintenanceTaskSettings = environment.maintenanceTaskSettings,
        personEnrichmentTaskSettings = environment.personEnrichmentTaskSettings,
    ),
)

private fun coreModule() = module {
    single { ApplicationState() }
    single { Clock.systemDefaultZone() }
}

internal fun Environment.httpClientsSettings() = HttpClientsSettings(
    aaregSettings = aaregSettings,
    altinnApiSettings = altinnApiSettings,
    altinnAuthorizationSettings = altinnAuthorizationSettings,
    altinnAccessSettings = altinnAccessSettings,
    dinesykmeldteSettings = dinesykmeldteSettings,
    dialogportenSettings = dialogportenSettings,
    eregSettings = eregSettings,
    pdlSettings = pdlSettings,
)
