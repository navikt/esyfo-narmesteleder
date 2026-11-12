package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.engine.HttpClientEngine
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.narmestelederbehov.narmestelederbehovDependencies
import no.nav.syfo.narmestelederrelasjon.narmestelederrelasjonDependencies
import no.nav.syfo.narmestelederstatistikk.narmestelederstatistikkDependencies
import no.nav.syfo.organisasjonstilgang.organisasjonstilgangDependencies
import no.nav.syfo.platform.auth.texas.TexasSettings
import no.nav.syfo.platform.cache.ValkeySettings
import no.nav.syfo.sykmelding.sykmeldingDependencies
import org.apache.kafka.clients.producer.Producer
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.reflect.KClass
import kotlin.time.Duration

class DependencyInjectionTest :
    FunSpec({
        mapOf(
            "local" to Configuration(localApplicationSettings(), localApplicationModules()),
            "NAIS" to naisApplicationSettings().let { Configuration(it, applicationModules(it)) },
        ).forEach { (name, configuration) ->
            val (settings, modules) = configuration

            test("every registered constructor dependency is defined for $name configuration") {
                module {
                    includes(modules)
                    includes(capabilityDependencies(settings))
                }.verify(extraTypes = valuesNotRegisteredInKoin)
            }

            // Production allows overrides, so a duplicate definition would otherwise replace another silently.
            test("no definition is registered twice for $name configuration") {
                koinApplication(createEagerInstances = false) {
                    allowOverride(false)
                    modules(modules + capabilityDependencies(settings))
                }.close()
            }
        }

        test("local runs replace every external client and leader election with a fake") {
            val localTypes = (localExternalClientsModule().definedTypes() + localLeaderElectionModule().definedTypes())

            localTypes shouldBe replaceableTypes()
        }

        test("production registers only real external clients and leader election") {
            replaceableImplementations(applicationModules(naisApplicationSettings())).forAll {
                it.simpleName shouldNotStartWith "Fake"
            }
        }

        test("local runs register only fake external clients and leader election") {
            replaceableImplementations(localApplicationModules()).forAll {
                it.simpleName shouldStartWith "Fake"
            }
        }
    })

private data class Configuration(val settings: ApplicationSettings, val modules: List<Module>)

private fun localApplicationModules() = applicationModules(
    settings = localApplicationSettings(),
    externalClients = localExternalClientsModule(),
    leaderElection = localLeaderElectionModule(),
)

private fun naisApplicationSettings() = settingsFromManifest("nais/nais-prod.yaml")

// Registered by each capability's Ktor module with koinModules(), not by applicationModules.
private fun capabilityDependencies(settings: ApplicationSettings) = listOf(
    organisasjonstilgangDependencies(),
    narmestelederrelasjonDependencies(),
    narmestelederbehovDependencies(settings.narmestelederbehov),
    narmestelederstatistikkDependencies(),
    sykmeldingDependencies(),
)

// Read from the real modules, so newly added clients are checked automatically.
private fun replaceableTypes(): Set<KClass<*>> {
    val settings = localApplicationSettings()
    return externalClientsModule(settings).definedTypes() +
        leaderElectionModule(settings.leaderElection).definedTypes()
}

private fun replaceableImplementations(modules: List<Module>): List<KClass<*>> {
    val application = koinApplication(createEagerInstances = false) { modules(modules) }
    try {
        return replaceableTypes().map { type -> application.koin.get<Any>(type).unwrapCache()::class }
    } finally {
        application.close()
    }
}

@OptIn(KoinInternalApi::class)
private fun Module.definedTypes(): Set<KClass<*>> = mappings.values.map { it.beanDefinition.primaryType }.toSet()

// Caching decorators wrap the real or fake client, so report the client they delegate to.
private fun Any.unwrapCache(): Any = when (this) {
    is CachedEregClient -> CachedEregClient::class.java.getDeclaredField("delegate").apply { isAccessible = true }.get(this)
    else -> this
}

// Values passed from bootstrap configuration or created by third-party constructors inside the definitions.
private val valuesNotRegisteredInKoin = listOf(
    Boolean::class,
    Duration::class,
    TexasSettings::class,
    ValkeySettings::class,
    HttpClientEngine::class,
    Producer::class,
)
