package no.nav.syfo.application.kafka

import no.nav.syfo.application.getEnvVar

data class KafkaEnvironment(
    val brokerUrl: String,
    val schemaRegistry: KafkaSchemaRegistryEnv,
    val sslConfig: KafkaSslEnv?,
) {
    companion object {
        fun createFromEnvVars() : KafkaEnvironment =
            KafkaEnvironment(
                brokerUrl = getEnvVar("KAFKA_BROKERS"),
                schemaRegistry = KafkaSchemaRegistryEnv(
                    url = getEnvVar("KAFKA_SCHEMA_REGISTRY"),
                    username = getEnvVar("KAFKA_SCHEMA_REGISTRY_USER"),
                    password = getEnvVar("KAFKA_SCHEMA_REGISTRY_PASSWORD"),
                ),
                sslConfig = KafkaSslEnv(
                    truststoreLocation = getEnvVar("KAFKA_TRUSTSTORE_PATH"),
                    keystoreLocation = getEnvVar("KAFKA_KEYSTORE_PATH"),
                    credstorePassword = getEnvVar("KAFKA_CREDSTORE_PASSWORD"),
                ),
            )
        fun createForLocal(): KafkaEnvironment =
            KafkaEnvironment(
                brokerUrl = "http://localhost:9092",
                schemaRegistry = KafkaSchemaRegistryEnv(
                    url = "http://localhost:8081",
                    username = null,
                    password = null,
                ),
                sslConfig = null
            )
    }
}

data class KafkaSslEnv(
    val truststoreLocation: String,
    val keystoreLocation: String,
    val credstorePassword: String,
)

data class KafkaSchemaRegistryEnv(
    val url: String,
    val username: String?,
    val password: String?,
)
