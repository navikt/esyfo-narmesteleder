package no.nav.syfo.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.ktor.client.engine.HttpClientEngine
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
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.time.Duration

class DependencyInjectionTest :
    FunSpec({
        mapOf(
            "local" to (LocalEnvironment() to true),
            "NAIS" to (naisEnvironmentWithoutEnvVars() to false),
        ).forEach { (name, configuration) ->
            val (environment, isLocalEnv) = configuration

            test("every registered constructor dependency is defined for $name configuration") {
                module {
                    includes(applicationModules(environment, isLocalEnv))
                    includes(capabilityDependencies(environment))
                }.verify(extraTypes = valuesNotRegisteredInKoin)
            }

            // Production allows overrides, so a duplicate definition would otherwise replace another silently.
            test("no definition is registered twice for $name configuration") {
                koinApplication(createEagerInstances = false) {
                    allowOverride(false)
                    modules(applicationModules(environment, isLocalEnv) + capabilityDependencies(environment))
                }.close()
            }
        }
    })

// NaisEnvironment() reads required NAIS variables, so supply every part explicitly.
private fun naisEnvironmentWithoutEnvVars() = NaisEnvironment(
    database = DatabaseEnvironment.createForLocal(),
    texas = TexasEnvironment.createForLocal(),
    valkeyEnvironment = ValkeyEnvironment.createForLocal(),
    kafka = KafkaEnvironment.createForLocal(),
    clientProperties = ClientProperties.createForLocal(),
    otherProperties = OtherEnvironmentProperties.createForLocal(),
)

// Registered by each capability's Ktor module with koinModules(), not by applicationModules.
private fun capabilityDependencies(environment: Environment) = listOf(
    organisasjonstilgangDependencies(),
    narmestelederrelasjonDependencies(environment.narmestelederrelasjonSettings()),
    narmestelederbehovDependencies(environment.narmestelederbehovSettings()),
    narmestelederstatistikkDependencies(),
    sykmeldingDependencies(),
)

// Values passed from bootstrap configuration or created by third-party constructors inside the definitions.
private val valuesNotRegisteredInKoin = listOf(
    Boolean::class,
    Duration::class,
    TexasEnvironment::class,
    ValkeyEnvironment::class,
    HttpClientEngine::class,
    Producer::class,
)
