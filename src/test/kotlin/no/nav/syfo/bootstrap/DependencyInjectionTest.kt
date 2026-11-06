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
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.createForLocal
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.cache.createForLocal
import no.nav.syfo.platform.config.applicationConfigWithEnvironment
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.database.createForLocal
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.kafka.createForLocal
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
            "local" to Configuration(LocalEnvironment(), localApplicationModules()),
            "NAIS" to naisEnvironmentWithoutEnvVars().let { Configuration(it, applicationModules(it)) },
        ).forEach { (name, configuration) ->
            val (environment, modules) = configuration

            test("every registered constructor dependency is defined for $name configuration") {
                module {
                    includes(modules)
                    includes(capabilityDependencies(environment))
                }.verify(extraTypes = valuesNotRegisteredInKoin)
            }

            // Production allows overrides, so a duplicate definition would otherwise replace another silently.
            test("no definition is registered twice for $name configuration") {
                koinApplication(createEagerInstances = false) {
                    allowOverride(false)
                    modules(modules + capabilityDependencies(environment))
                }.close()
            }
        }

        test("local runs replace every external client and leader election with a fake") {
            val localTypes = (localExternalClientsModule().definedTypes() + localLeaderElectionModule().definedTypes())

            localTypes shouldBe replaceableTypes()
        }

        test("production registers only real external clients and leader election") {
            replaceableImplementations(applicationModules(naisEnvironmentWithoutEnvVars())).forAll {
                it.simpleName shouldNotStartWith "Fake"
            }
        }

        test("local runs register only fake external clients and leader election") {
            replaceableImplementations(localApplicationModules()).forAll {
                it.simpleName shouldStartWith "Fake"
            }
        }
    })

private data class Configuration(val environment: Environment, val modules: List<Module>)

private fun localApplicationModules() = applicationModules(
    environment = LocalEnvironment(),
    externalClients = localExternalClientsModule(),
    leaderElection = localLeaderElectionModule(),
)

// NaisEnvironment reads infrastructure from env vars, so supply local values and synthetic required settings.
private fun naisEnvironmentWithoutEnvVars(): NaisEnvironment {
    val localEnvironment = LocalEnvironment()
    return NaisEnvironment(
        settings = ApplicationSettings.from(applicationConfigWithEnvironment(requiredNaisVariables)),
        database = DatabaseEnvironment.createForLocal(),
        texas = TexasEnvironment.createForLocal(),
        valkeyEnvironment = ValkeyEnvironment.createForLocal(),
        kafka = KafkaEnvironment.createForLocal(),
        aaregSettings = localEnvironment.aaregSettings,
        altinnApiSettings = localEnvironment.altinnApiSettings,
        altinnAuthorizationSettings = localEnvironment.altinnAuthorizationSettings,
        altinnAccessSettings = localEnvironment.altinnAccessSettings,
        dinesykmeldteSettings = localEnvironment.dinesykmeldteSettings,
        dialogportenSettings = localEnvironment.dialogportenSettings,
        eregSettings = localEnvironment.eregSettings,
        pdlSettings = localEnvironment.pdlSettings,
    )
}

private val requiredNaisVariables = mapOf(
    "PUBLIC_INGRESS_URL" to "https://ingress.example",
    "FRONTEND_BASE_URL" to "https://frontend.example",
    "DIALOGPORTEN_TASK_ENABLED" to "true",
    "ELECTOR_PATH" to "elector-path",
    "ELECTOR_SSE_URL" to "http://elector.example/sse",
)

// Registered by each capability's Ktor module with koinModules(), not by applicationModules.
private fun capabilityDependencies(environment: Environment) = listOf(
    organisasjonstilgangDependencies(),
    narmestelederrelasjonDependencies(environment.narmestelederrelasjonEmploymentSettings),
    narmestelederbehovDependencies(environment.narmestelederbehovSettings),
    narmestelederstatistikkDependencies(),
    sykmeldingDependencies(),
)

// Read from the real modules, so newly added clients are checked automatically.
private fun replaceableTypes(): Set<KClass<*>> {
    val environment = LocalEnvironment()
    return externalClientsModule(environment.httpClientsSettings()).definedTypes() +
        leaderElectionModule(environment.leaderElectionSettings).definedTypes()
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
    TexasEnvironment::class,
    ValkeyEnvironment::class,
    HttpClientEngine::class,
    Producer::class,
)
