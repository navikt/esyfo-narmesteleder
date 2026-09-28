package no.nav.syfo.plugins

import io.kotest.core.spec.style.FunSpec
import io.ktor.client.engine.HttpClientEngine
import no.nav.syfo.application.environment.OtherEnvironmentProperties
import no.nav.syfo.application.texas.TexasEnvironment
import no.nav.syfo.application.valkey.ValkeyEnvironment
import org.apache.kafka.clients.producer.KafkaProducer
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.time.Duration

class DependencyInjectionTest :
    FunSpec({
        listOf(true, false).forEach { isLocalEnv ->
            test("every registered constructor dependency is defined when isLocalEnv=$isLocalEnv") {
                module { includes(applicationModules(isLocalEnv)) }.verify(extraTypes = valuesNotRegisteredInKoin)
            }
        }
    })

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
