package no.nav.syfo.bootstrap

import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.config.getEnvVar
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.kafka.KafkaEnvironment

interface Environment {
    val database: DatabaseEnvironment
    val texas: TexasEnvironment
    val valkeyEnvironment: ValkeyEnvironment
    val kafka: KafkaEnvironment
    val clientProperties: ClientProperties
    val otherProperties: OtherEnvironmentProperties
}

data class NaisEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createFromEnvVars(),
    override val texas: TexasEnvironment = TexasEnvironment.createFromEnvVars(),
    override val valkeyEnvironment: ValkeyEnvironment = ValkeyEnvironment.createFromEnvVars(),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createFromEnvVars(),
    override val clientProperties: ClientProperties = ClientProperties.createFromEnvVars(),
    override val otherProperties: OtherEnvironmentProperties = OtherEnvironmentProperties.createFromEnvVars()
) : Environment

fun isLocalEnv(): Boolean = getEnvVar("NAIS_CLUSTER_NAME", "local") == "local"

fun loadEnvironment(isLocal: Boolean): Environment = if (isLocal) LocalEnvironment() else NaisEnvironment()

data class LocalEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createForLocal(),
    override val texas: TexasEnvironment = TexasEnvironment.createForLocal(),
    override val valkeyEnvironment: ValkeyEnvironment = ValkeyEnvironment.createForLocal(),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createForLocal(),
    override val clientProperties: ClientProperties = ClientProperties.createForLocal(),
    override val otherProperties: OtherEnvironmentProperties = OtherEnvironmentProperties.createForLocal()
) : Environment
