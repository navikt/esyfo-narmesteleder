package no.nav.syfo.plugins

import io.kotest.core.spec.style.FunSpec
import io.ktor.client.engine.HttpClientEngine
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.texas.TexasEnvironment
import no.nav.syfo.application.valkey.ValkeyEnvironment
import no.nav.syfo.narmestelederstatistikk.narmestelederstatistikkDependencies
import org.apache.kafka.clients.producer.KafkaProducer
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.time.Duration

class DependencyInjectionTest :
    FunSpec({
        listOf(true, false).forEach { isLocalEnv ->
            test("every registered constructor dependency is defined when isLocalEnv=$isLocalEnv") {
                module {
                    includes(applicationModules(isLocalEnv))
                    includes(capabilityDependencies)
                }.verify(extraTypes = valuesNotRegisteredInKoin)
            }

            // Production allows overrides, so a duplicate definition would otherwise replace another silently.
            test("no definition is registered twice when isLocalEnv=$isLocalEnv") {
                koinApplication(createEagerInstances = false) {
                    allowOverride(false)
                    modules(applicationModules(isLocalEnv) + capabilityDependencies)
                }.close()
            }
        }
    })

// Registered by each capability's Ktor module with koinModules(), not by applicationModules.
private val capabilityDependencies = listOf(
    narmestelederstatistikkDependencies(),
)

// Values read from Environment or created by third-party constructors inside the definitions.
private val valuesNotRegisteredInKoin = listOf(
    Boolean::class,
    Duration::class,
    OtherEnvironmentProperties::class,
    TexasEnvironment::class,
    ValkeyEnvironment::class,
    HttpClientEngine::class,
    KafkaProducer::class,
)
