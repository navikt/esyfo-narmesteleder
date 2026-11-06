package no.nav.syfo.bootstrap

import no.nav.syfo.platform.auth.texas.TexasSettings
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings
import no.nav.syfo.platform.auth.texas.altinnTilgangerExchangeTarget
import no.nav.syfo.platform.auth.texas.preAuthorizedAppsFromJsonOrNull
import no.nav.syfo.platform.cache.ValkeySettings
import no.nav.syfo.platform.config.ConfigReader
import no.nav.syfo.platform.database.DatabaseSettings
import no.nav.syfo.platform.database.DatabaseSsl
import no.nav.syfo.platform.kafka.KafkaSchemaRegistrySettings
import no.nav.syfo.platform.kafka.KafkaSettings
import no.nav.syfo.platform.kafka.KafkaSslSettings
import no.nav.syfo.platform.scheduling.LeaderElectionSettings

internal fun ConfigReader.databaseSettings() = DatabaseSettings(
    host = string("database.host"),
    port = string("database.port"),
    name = string("database.name"),
    username = string("database.username"),
    password = string("database.password"),
    ssl = if (flag("database.ssl.enabled")) {
        DatabaseSsl(
            cert = string("database.ssl.cert"),
            key = string("database.ssl.key"),
            rootCert = string("database.ssl.rootCert"),
            mode = string("database.ssl.mode"),
        )
    } else {
        null
    },
)

internal fun ConfigReader.valkeySettings() = ValkeySettings(
    host = string("valkey.host"),
    port = int("valkey.port"),
    username = string("valkey.username"),
    password = string("valkey.password"),
    ssl = flag("valkey.ssl"),
)

internal fun ConfigReader.kafkaSettings() = KafkaSettings(
    brokerUrl = string("kafka.brokers"),
    schemaRegistry = schemaRegistrySettings(),
    sslConfig = if (flag("kafka.ssl.enabled")) {
        KafkaSslSettings(
            truststoreLocation = string("kafka.ssl.truststorePath"),
            keystoreLocation = string("kafka.ssl.keystorePath"),
            credstorePassword = string("kafka.ssl.credstorePassword"),
        )
    } else {
        null
    },
    commitOnAllErrors = flag("kafka.commitOnAllErrors"),
    shouldConsumeTopics = flag("kafka.consumeTopics"),
)

internal fun ConfigReader.texasSettings() = TexasSettings(
    tokenIntrospectionEndpoint = string("texas.tokenIntrospectionEndpoint"),
    tokenEndpoint = string("texas.tokenEndpoint"),
    tokenExchangeEndpoint = string("texas.tokenExchangeEndpoint"),
    exchangeTargetIsAltinnTilganger = altinnTilgangerExchangeTarget(string("texas.altinnTilgangerCluster")),
    azurePreAuthorizedApps = parsed("texas.azurePreAuthorizedApps", "is not a valid JSON list of applications", emptySet()) {
        preAuthorizedAppsFromJsonOrNull(it)
    },
)

internal fun ConfigReader.altinnApiSettings() = AltinnApiSettings(baseUrl = string("altinn.baseUrl"))

internal fun ConfigReader.leaderElectionSettings() = LeaderElectionSettings(
    electorPath = string("leaderElection.path"),
    electorSseUrl = string("leaderElection.sseUrl"),
)

internal fun ConfigReader.maintenanceTaskSettings() = MaintenanceTaskSettings(
    enabled = flag("maintenanceTask.enabled"),
    interval = duration("maintenanceTask.interval"),
)

private fun ConfigReader.schemaRegistrySettings(): KafkaSchemaRegistrySettings {
    val url = string("kafka.schemaRegistry.url")
    return if (flag("kafka.schemaRegistry.authEnabled")) {
        KafkaSchemaRegistrySettings(url, string("kafka.schemaRegistry.username"), string("kafka.schemaRegistry.password"))
    } else {
        KafkaSchemaRegistrySettings(url, username = null, password = null)
    }
}
