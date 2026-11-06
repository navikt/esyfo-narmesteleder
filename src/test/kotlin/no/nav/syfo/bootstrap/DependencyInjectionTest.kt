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
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.kafka.KafkaEnvironment
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
            "local" to Configuration(LocalEnvironment(), isLocalEnv = true, localExternalClientsModule()),
            "NAIS" to Configuration(naisEnvironmentWithoutEnvVars(), isLocalEnv = false, externalClients = null),
        ).forEach { (name, configuration) ->
            val (environment, isLocalEnv, externalClients) = configuration

            test("every registered constructor dependency is defined for $name configuration") {
                module {
                    includes(applicationModules(environment, isLocalEnv, externalClients))
                    includes(capabilityDependencies(environment))
                }.verify(extraTypes = valuesNotRegisteredInKoin)
            }

            // Production allows overrides, so a duplicate definition would otherwise replace another silently.
            test("no definition is registered twice for $name configuration") {
                koinApplication(createEagerInstances = false) {
                    allowOverride(false)
                    modules(applicationModules(environment, isLocalEnv, externalClients) + capabilityDependencies(environment))
                }.close()
            }
        }

        test("local runs replace every external client with a fake") {
            val localTypes = localExternalClientsModule().definedTypes()

            localTypes shouldBe externalClientsModule(LocalEnvironment().httpClientsSettings()).definedTypes()
        }

        test("production registers only real external clients") {
            val production = naisEnvironmentWithoutEnvVars()

            externalClientImplementations(applicationModules(production, isLocalEnv = false)).forAll {
                it.simpleName shouldNotStartWith "Fake"
            }
        }

        test("local runs register only fake external clients") {
            val local = LocalEnvironment()

            externalClientImplementations(applicationModules(local, isLocalEnv = true, localExternalClientsModule())).forAll {
                it.simpleName shouldStartWith "Fake"
            }
        }
    })

private data class Configuration(val environment: Environment, val isLocalEnv: Boolean, val externalClients: Module?)

// NaisEnvironment() reads required NAIS variables, so supply every part explicitly.
private fun naisEnvironmentWithoutEnvVars(): NaisEnvironment {
    val localEnvironment = LocalEnvironment()
    return NaisEnvironment(
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
        narmestelederbehovSettings = localEnvironment.narmestelederbehovSettings,
        narmestelederbehovExpirySettings = localEnvironment.narmestelederbehovExpirySettings,
        narmestelederrelasjonEmploymentSettings = localEnvironment.narmestelederrelasjonEmploymentSettings,
        narmestelederrelasjonKafkaSettings = localEnvironment.narmestelederrelasjonKafkaSettings,
        sykmeldingKafkaSettings = localEnvironment.sykmeldingKafkaSettings,
        dialogportenTaskSettings = localEnvironment.dialogportenTaskSettings,
        maintenanceTaskSettings = localEnvironment.maintenanceTaskSettings,
        personEnrichmentTaskSettings = localEnvironment.personEnrichmentTaskSettings,
        leaderElectionSettings = localEnvironment.leaderElectionSettings,
    )
}

// Registered by each capability's Ktor module with koinModules(), not by applicationModules.
private fun capabilityDependencies(environment: Environment) = listOf(
    organisasjonstilgangDependencies(),
    narmestelederrelasjonDependencies(environment.narmestelederrelasjonEmploymentSettings),
    narmestelederbehovDependencies(environment.narmestelederbehovSettings),
    narmestelederstatistikkDependencies(),
    sykmeldingDependencies(),
)

// Every type the real external clients module defines, so newly added clients are checked automatically.
private fun externalClientImplementations(modules: List<Module>): List<KClass<*>> {
    val clientTypes = externalClientsModule(LocalEnvironment().httpClientsSettings()).definedTypes()
    val application = koinApplication(createEagerInstances = false) { modules(modules) }
    try {
        return clientTypes.map { type -> application.koin.get<Any>(type).unwrapCache()::class }
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
