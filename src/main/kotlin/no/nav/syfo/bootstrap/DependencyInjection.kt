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
    settings: ApplicationSettings,
    externalClients: Module? = null,
    leaderElection: Module? = null,
): List<Module> = listOf(
    coreModule(),
    databaseModule(settings.database),
    httpClientsModule(settings = settings.httpClientsSettings(), texas = settings.texas),
    externalClients ?: externalClientsModule(settings.httpClientsSettings()),
    cacheModule(settings.valkey),
    kafkaProducersModule(settings.kafka),
    leaderElection ?: leaderElectionModule(settings.leaderElectionSettings),
    tasksModule(
        narmestelederbehovExpirySettings = settings.narmestelederbehovExpirySettings,
        maintenanceTaskSettings = settings.maintenanceTaskSettings,
        personEnrichmentTaskSettings = settings.personEnrichmentTaskSettings,
    ),
)

private fun coreModule() = module {
    single { ApplicationState() }
    single { Clock.systemDefaultZone() }
}

internal fun ApplicationSettings.httpClientsSettings() = HttpClientsSettings(
    aaregSettings = aaregSettings,
    altinnApiSettings = altinnApiSettings,
    altinnAuthorizationSettings = altinnAuthorizationSettings,
    altinnAccessSettings = altinnAccessSettings,
    dinesykmeldteSettings = dinesykmeldteSettings,
    dialogportenSettings = dialogportenSettings,
    eregSettings = eregSettings,
    pdlSettings = pdlSettings,
)
