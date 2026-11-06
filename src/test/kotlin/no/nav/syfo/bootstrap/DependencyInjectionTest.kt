package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.HttpClientEngine
import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.FakeAaregClient
import no.nav.syfo.integration.aareg.HttpAaregClient
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.integration.dinesykmeldte.FakeDinesykmeldteClient
import no.nav.syfo.integration.dinesykmeldte.HttpDinesykmeldteClient
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.ereg.HttpEregClient
import no.nav.syfo.integration.pdl.FakePdlClient
import no.nav.syfo.integration.pdl.HttpPdlClient
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.FakeDialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.narmestelederbehov.narmestelederbehovDependencies
import no.nav.syfo.narmestelederrelasjon.narmestelederrelasjonDependencies
import no.nav.syfo.narmestelederstatistikk.narmestelederstatistikkDependencies
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.FakeAltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.HttpAltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.FakeAltinnTilgangerClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.HttpAltinnTilgangerClient
import no.nav.syfo.organisasjonstilgang.organisasjonstilgangDependencies
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.sykmelding.sykmeldingDependencies
import org.apache.kafka.clients.producer.Producer
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

        test("production registers the real external clients") {
            externalClientTypes(applicationModules(naisEnvironmentWithoutEnvVars(), isLocalEnv = false)) shouldBe mapOf(
                AaregClient::class to HttpAaregClient::class,
                DinesykmeldteClient::class to HttpDinesykmeldteClient::class,
                PdlClient::class to HttpPdlClient::class,
                AltinnTilgangerClient::class to HttpAltinnTilgangerClient::class,
                DialogportenClient::class to HttpDialogportenClient::class,
                EregClient::class to HttpEregClient::class,
                AltinnAuthorizationClient::class to HttpAltinnAuthorizationClient::class,
            )
        }

        test("local runs register the fake external clients") {
            val modules = applicationModules(LocalEnvironment(), isLocalEnv = true, localExternalClientsModule())

            externalClientTypes(modules) shouldBe mapOf(
                AaregClient::class to FakeAaregClient::class,
                DinesykmeldteClient::class to FakeDinesykmeldteClient::class,
                PdlClient::class to FakePdlClient::class,
                AltinnTilgangerClient::class to FakeAltinnTilgangerClient::class,
                DialogportenClient::class to FakeDialogportenClient::class,
                EregClient::class to FakeEregClient::class,
                AltinnAuthorizationClient::class to FakeAltinnAuthorizationClient::class,
            )
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

// The Ereg client is resolved through its cache, so report the wrapped client it delegates to.
private fun externalClientTypes(modules: List<Module>): Map<KClass<*>, KClass<*>> {
    val application = koinApplication(createEagerInstances = false) { modules(modules) }
    try {
        val koin = application.koin
        val ereg = koin.get<EregClient>().shouldBeInstanceOf<CachedEregClient>()
        return mapOf(
            AaregClient::class to koin.get<AaregClient>()::class,
            DinesykmeldteClient::class to koin.get<DinesykmeldteClient>()::class,
            PdlClient::class to koin.get<PdlClient>()::class,
            AltinnTilgangerClient::class to koin.get<AltinnTilgangerClient>()::class,
            DialogportenClient::class to koin.get<DialogportenClient>()::class,
            EregClient::class to ereg.delegateClass(),
            AltinnAuthorizationClient::class to koin.get<AltinnAuthorizationClient>()::class,
        )
    } finally {
        application.close()
    }
}

private fun CachedEregClient.delegateClass(): KClass<*> = CachedEregClient::class.java.getDeclaredField("delegate").apply { isAccessible = true }.get(this)::class

// Values passed from bootstrap configuration or created by third-party constructors inside the definitions.
private val valuesNotRegisteredInKoin = listOf(
    Boolean::class,
    Duration::class,
    TexasEnvironment::class,
    ValkeyEnvironment::class,
    HttpClientEngine::class,
    Producer::class,
)
