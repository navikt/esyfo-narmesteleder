package no.nav.syfo.platform.kafka

data class KafkaEnvironment(
    val brokerUrl: String,
    val schemaRegistry: KafkaSchemaRegistryEnv,
    /** Null only for local runs, where the broker has no TLS. */
    val sslConfig: KafkaSslEnv?,
    val commitOnAllErrors: Boolean,
    val shouldConsumeTopics: Boolean,
)

data class KafkaSslEnv(
    val truststoreLocation: String,
    val keystoreLocation: String,
    val credstorePassword: String,
) {
    override fun toString() = "KafkaSslEnv(truststoreLocation=$truststoreLocation, keystoreLocation=$keystoreLocation, credstorePassword=[REDACTED])"
}

data class KafkaSchemaRegistryEnv(
    val url: String,
    val username: String?,
    val password: String?,
) {
    override fun toString() = "KafkaSchemaRegistryEnv(url=$url, username=$username, password=${password?.let { "[REDACTED]" }})"
}
