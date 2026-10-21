package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.install
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings
import no.nav.syfo.platform.observability.ApplicationState
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.time.Clock
import kotlin.time.Duration

fun Application.configureDependencies(koinModules: List<Module>) {
    install(Koin) {
        slf4jLogger()
        modules(koinModules)
    }
}

internal fun applicationModules(environment: Environment, isLocalEnv: Boolean): List<Module> = listOf(
    coreModule(),
    capabilitySettingsModule(environment),
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

/** The only place capabilities receive configuration; they never see [Environment]. */
private fun capabilitySettingsModule(environment: Environment) = module {
    val other = environment.otherProperties
    single {
        NarmestelederbehovSettings(
            persistLeesahNlBehov = other.persistLeesahNlBehov,
            publicIngressUrl = other.publicIngressUrl,
            frontendBaseUrl = other.frontendBaseUrl,
            dialogUpdatePollingInterval = Duration.parse(other.updateDialogportenTaskProperties.pollingDelay),
        )
    }
    single {
        NarmestelederrelasjonSettings(
            employmentCheckObserveSourceEnabled = other.employmentCheckObserveSourceEnabled,
            employmentCheckRevokeEnabled = other.employmentCheckRevokeEnabled,
            lineManagerLookupPreAuthorizedApps = environment.texas.azurePreAuthorizedApps,
        )
    }
}
