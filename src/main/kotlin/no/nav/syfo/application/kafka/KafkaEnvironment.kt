package no.nav.syfo.application.kafka

import no.nav.syfo.application.environment.getEnvVar

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

        fun createForLocal(): KafkaEnvironment = KafkaEnvironment(
            brokerUrl = "http://localhost:9092",
            schemaRegistry = KafkaSchemaRegistryEnv(
                url = "http://localhost:8081",
                username = null,
                password = null,
            ),
            sslConfig = null,
            // Dersom man under lokal testing har sendt en ugyldig kafka-melding
            // som feiler, og man ønsker å acknowledge den for å gå videre,
            // kan denne settes til true her eller med en env `KAFKA_COMMIT_ON_ERROR=true`
            commitOnAllErrors = getEnvVar("KAFKA_COMMIT_ON_ERROR", "false").toBoolean(),
            shouldConsumeTopics = true,
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
