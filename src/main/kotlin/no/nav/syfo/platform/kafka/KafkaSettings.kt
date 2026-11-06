package no.nav.syfo.platform.kafka

data class KafkaSettings(
    val brokerUrl: String,
    val schemaRegistry: KafkaSchemaRegistrySettings,
    /** Null only for local runs, where the broker has no TLS. */
    val sslConfig: KafkaSslSettings?,
    val commitOnAllErrors: Boolean,
    val shouldConsumeTopics: Boolean,
)

data class KafkaSslSettings(
    val truststoreLocation: String,
    val keystoreLocation: String,
    val credstorePassword: String,
) {
    override fun toString() = "KafkaSslSettings(truststoreLocation=$truststoreLocation, keystoreLocation=$keystoreLocation, credstorePassword=[REDACTED])"
}

data class KafkaSchemaRegistrySettings(
    val url: String,
    val username: String?,
    val password: String?,
) {
    override fun toString() = "KafkaSchemaRegistrySettings(url=$url, username=$username, password=${password?.let { "[REDACTED]" }})"
}
