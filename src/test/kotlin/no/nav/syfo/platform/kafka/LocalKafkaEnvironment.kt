package no.nav.syfo.platform.kafka

import no.nav.syfo.platform.config.getEnvVar

fun KafkaEnvironment.Companion.createForLocal(): KafkaEnvironment = KafkaEnvironment(
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
