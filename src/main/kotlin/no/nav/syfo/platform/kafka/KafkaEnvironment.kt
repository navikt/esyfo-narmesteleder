package no.nav.syfo.platform.kafka

import no.nav.syfo.platform.config.getEnvVar

data class KafkaEnvironment(
    val brokerUrl: String,
    val schemaRegistry: KafkaSchemaRegistryEnv,
    val sslConfig: KafkaSslEnv?,
    val commitOnAllErrors: Boolean = false,
    val shouldConsumeTopics: Boolean,
) {
    companion object {
        fun createFromEnvVars(): KafkaEnvironment = KafkaEnvironment(
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
            shouldConsumeTopics = getEnvVar("CONSUME_KAFKA_TOPICS").toBoolean()
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
