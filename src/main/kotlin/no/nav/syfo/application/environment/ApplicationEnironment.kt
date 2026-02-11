package no.nav.syfo.application.environment

import no.nav.syfo.application.database.DatabaseEnvironment
import no.nav.syfo.application.kafka.KafkaEnvironment
import no.nav.syfo.application.texas.TexasEnvironment
import no.nav.syfo.application.valkey.ValkeyEnvironment

interface Environment {
    val database: DatabaseEnvironment
    val texas: TexasEnvironment
    val valkeyEnvironment: ValkeyEnvironment
    val kafka: KafkaEnvironment
    val clientProperties: ClientProperties
    val otherProperties: OtherEnvironmentProperties
}

const val NAIS_DATABASE_ENV_PREFIX = "NARMESTELEDER_DB"

data class NaisEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createFromEnvVars(),
    override val texas: TexasEnvironment = TexasEnvironment.createFromEnvVars(),
    override val valkeyEnvironment: ValkeyEnvironment = ValkeyEnvironment(
        host = getEnvVar("VALKEY_HOST_FELLES_CACHE"),
        port = getEnvVar("VALKEY_PORT_FELLES_CACHE").toInt(),
        username = getEnvVar("VALKEY_USERNAME_FELLES_CACHE"),
        password = getEnvVar("VALKEY_PASSWORD_FELLES_CACHE")
    ),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createFromEnvVars(),
    override val clientProperties: ClientProperties = ClientProperties.createFromEnvVars(),
    override val otherProperties: OtherEnvironmentProperties = OtherEnvironmentProperties.createFromEnvVars()
) : Environment

fun getEnvVar(varName: String, defaultValue: String? = null) = System.getenv(varName) ?: defaultValue ?: throw RuntimeException("Missing required variable \"$varName\"")

fun isLocalEnv(): Boolean = getEnvVar("NAIS_CLUSTER_NAME", "local") == "local"

fun isProdEnv(): Boolean = getEnvVar("NAIS_CLUSTER_NAME", "local") == "prod-gcp"

data class LocalEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createForLocal(),
    override val texas: TexasEnvironment = TexasEnvironment.createForLocal(),
    override val valkeyEnvironment: ValkeyEnvironment = ValkeyEnvironment(
        host = "localhost",
        port = 6379,
        username = "default",
        password = "test",
        ssl = false
    ),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createForLocal(),
    override val clientProperties: ClientProperties = ClientProperties.createForLocal(),
    override val otherProperties: OtherEnvironmentProperties = OtherEnvironmentProperties.createForLocal()
) : Environment
