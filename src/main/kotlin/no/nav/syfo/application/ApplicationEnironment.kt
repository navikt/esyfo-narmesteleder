package no.nav.syfo.application

import no.nav.syfo.application.database.DatabaseEnvironment
import no.nav.syfo.application.kafka.KafkaEnvironment
import no.nav.syfo.application.texas.TexasEnvironment

interface Environment {
    val database: DatabaseEnvironment
    val texas: TexasEnvironment
    val kafka: KafkaEnvironment
}

const val NAIS_DATABASE_ENV_PREFIX = "NARMESTELEDER_DB"

data class NaisEnvironment(
//    override val database: DatabaseEnvironment = DatabaseEnvironment.createFromEnvVars(),
    override val database: DatabaseEnvironment =  DatabaseEnvironment(
        host = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_HOST"),
        port = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_PORT"),
        name = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_DATABASE"),
        username = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_USERNAME"),
        password = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_PASSWORD"),
        sslcert = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_SSLCERT"),
        sslkey = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_SSLKEY_PK8"),
        sslrootcert = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_SSLROOTCERT"),
        sslmode = getEnvVar("${NAIS_DATABASE_ENV_PREFIX}_SSLMODE"),
    ),
    override val texas: TexasEnvironment = TexasEnvironment.createFromEnvVars(),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createFromEnvVars()
) : Environment

fun getEnvVar(varName: String, defaultValue: String? = null) =
    System.getenv(varName) ?: defaultValue ?: throw RuntimeException("Missing required variable \"$varName\"")

fun isLocalEnv(): Boolean =
    getEnvVar("NAIS_CLUSTER_NAME", "local") == "local"

fun isProdEnv(): Boolean =
    getEnvVar("NAIS_CLUSTER_NAME", "local") == "prod-gcp"

data class LocalEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createForLocal(),
    override val texas: TexasEnvironment = TexasEnvironment.createForLocal(),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createForLocal()
) : Environment
