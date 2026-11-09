package no.nav.syfo.platform.kafka

data class KafkaSettings(
    val brokerUrl: String,
    val schemaRegistry: KafkaSchemaRegistrySettings,
    val security: KafkaSecurity,
    val commitOnAllErrors: Boolean,
    val shouldConsumeTopics: Boolean,
)

sealed interface KafkaSecurity {
    /** Local broker without TLS. */
    data object Plaintext : KafkaSecurity

    data class Ssl(
        val truststoreLocation: String,
        val keystoreLocation: String,
        val credstorePassword: String,
    ) : KafkaSecurity {
        override fun toString() = "Ssl(truststoreLocation=$truststoreLocation, keystoreLocation=$keystoreLocation, credstorePassword=[REDACTED])"
    }
}

data class KafkaSchemaRegistrySettings(
    val url: String,
    val auth: SchemaRegistryAuth,
)

sealed interface SchemaRegistryAuth {
    /** Local schema registry without authentication. */
    data object None : SchemaRegistryAuth

    data class Basic(
        val username: String,
        val password: String,
    ) : SchemaRegistryAuth {
        override fun toString() = "Basic(username=$username, password=[REDACTED])"
    }
}
