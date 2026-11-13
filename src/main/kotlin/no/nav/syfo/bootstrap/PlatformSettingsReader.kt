package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.platform.auth.texas.TexasSettings
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings
import no.nav.syfo.platform.auth.texas.altinnTilgangerExchangeTarget
import no.nav.syfo.platform.cache.ValkeySettings
import no.nav.syfo.platform.config.duration
import no.nav.syfo.platform.config.flag
import no.nav.syfo.platform.config.int
import no.nav.syfo.platform.config.string
import no.nav.syfo.platform.database.DatabaseSecurity
import no.nav.syfo.platform.database.DatabaseSettings
import no.nav.syfo.platform.kafka.KafkaSchemaRegistrySettings
import no.nav.syfo.platform.kafka.KafkaSecurity
import no.nav.syfo.platform.kafka.KafkaSettings
import no.nav.syfo.platform.kafka.SchemaRegistryAuth
import no.nav.syfo.platform.scheduling.LeaderElectionSettings

internal fun ApplicationConfig.databaseSettings() = DatabaseSettings(
    host = string("database.host"),
    port = string("database.port"),
    name = string("database.name"),
    username = string("database.username"),
    password = string("database.password"),
    security = if (flag("database.ssl.enabled")) {
        DatabaseSecurity.Ssl(
            cert = string("database.ssl.cert"),
            key = string("database.ssl.key"),
            rootCert = string("database.ssl.rootCert"),
            mode = string("database.ssl.mode"),
        )
    } else {
        DatabaseSecurity.Plaintext
    },
)

internal fun ApplicationConfig.valkeySettings() = ValkeySettings(
    host = string("valkey.host"),
    port = int("valkey.port"),
    username = string("valkey.username"),
    password = string("valkey.password"),
    ssl = flag("valkey.ssl"),
)

internal fun ApplicationConfig.kafkaSettings() = KafkaSettings(
    brokerUrl = string("kafka.brokers"),
    schemaRegistry = schemaRegistrySettings(),
    security = if (flag("kafka.ssl.enabled")) {
        KafkaSecurity.Ssl(
            truststoreLocation = string("kafka.ssl.truststorePath"),
            keystoreLocation = string("kafka.ssl.keystorePath"),
            credstorePassword = string("kafka.ssl.credstorePassword"),
        )
    } else {
        KafkaSecurity.Plaintext
    },
    commitOnAllErrors = flag("kafka.commitOnAllErrors"),
)

internal fun ApplicationConfig.texasSettings() = TexasSettings(
    tokenIntrospectionEndpoint = string("texas.tokenIntrospectionEndpoint"),
    tokenEndpoint = string("texas.tokenEndpoint"),
    tokenExchangeEndpoint = string("texas.tokenExchangeEndpoint"),
    exchangeTargetIsAltinnTilganger = altinnTilgangerExchangeTarget(string("texas.altinnTilgangerCluster")),
)

internal fun ApplicationConfig.altinnApiSettings() = AltinnApiSettings(baseUrl = string("altinn.baseUrl"))

internal fun ApplicationConfig.leaderElectionSettings() = LeaderElectionSettings(
    electorPath = string("leaderElection.path"),
    electorSseUrl = string("leaderElection.sseUrl"),
)

internal fun ApplicationConfig.maintenanceTaskSettings() = MaintenanceTaskSettings(
    interval = duration("maintenanceTask.interval"),
)

private fun ApplicationConfig.schemaRegistrySettings() = KafkaSchemaRegistrySettings(
    url = string("kafka.schemaRegistry.url"),
    auth = if (flag("kafka.schemaRegistry.authEnabled")) {
        SchemaRegistryAuth.Basic(
            username = string("kafka.schemaRegistry.username"),
            password = string("kafka.schemaRegistry.password"),
        )
    } else {
        SchemaRegistryAuth.None
    },
)
