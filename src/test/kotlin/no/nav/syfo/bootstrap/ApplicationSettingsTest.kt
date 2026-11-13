package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.platform.config.applicationConfigWithEnvironment
import no.nav.syfo.platform.config.configResources
import no.nav.syfo.platform.database.DatabaseSecurity
import no.nav.syfo.platform.database.DatabaseSettings
import no.nav.syfo.platform.kafka.KafkaSchemaRegistrySettings
import no.nav.syfo.platform.kafka.KafkaSecurity
import no.nav.syfo.platform.kafka.SchemaRegistryAuth

class ApplicationSettingsTest :
    FunSpec({
        test("Kafka commits on errors are disabled unless NAIS enables them") {
            assertSoftly(settingsFrom(requiredEnvironment)) {
                kafka.commitOnAllErrors shouldBe false
            }
        }

        test("startup names the missing setting") {
            val failure = shouldThrow<IllegalStateException> { settingsFrom(requiredEnvironment - "NARMESTELEDER_DB_HOST") }

            failure.message shouldBe "Invalid configuration: database.host is missing"
        }

        listOf(
            Triple("narmestelederbehov.expiry.daysAfterTom", "sixteen", "is not a whole number"),
            Triple("narmestelederbehov.dialogporten.updatePollingInterval", "soon", "is not a valid duration"),
        ).forEach { (path, value, problem) ->
            test("startup reports an invalid $path without the value") {
                val failure = shouldThrow<IllegalStateException> { settingsFrom(requiredEnvironment, overrides = mapOf(path to value)) }

                assertSoftly(failure.message.orEmpty()) {
                    this shouldBe "Invalid configuration: $path $problem"
                    this shouldNotContain value
                }
            }
        }

        test("the database connects to Cloud SQL with the NAIS certificates") {
            val database = settingsFrom(requiredEnvironment).database

            assertSoftly {
                database shouldBe DatabaseSettings(
                    host = "db.example",
                    port = "5432",
                    name = "narmesteleder",
                    username = "db-user",
                    password = "db-password",
                    security = DatabaseSecurity.Ssl(
                        cert = "/certs/cert.pem",
                        key = "/certs/key.pk8",
                        rootCert = "/certs/root.pem",
                        mode = "verify-ca",
                    ),
                )
                database.jdbcUrl() shouldBe "jdbc:postgresql://db.example:5432/narmesteleder" +
                    "?ssl=on&sslrootcert=/certs/root.pem&sslcert=/certs/cert.pem&sslmode=verify-ca&sslkey=/certs/key.pk8"
            }
        }

        test("Kafka connects to Aiven with the NAIS keystore") {
            val kafka = settingsFrom(requiredEnvironment).kafka

            assertSoftly {
                kafka.brokerUrl shouldBe "kafka.example:9093"
                kafka.security shouldBe KafkaSecurity.Ssl(
                    truststoreLocation = "/kafka/truststore.jks",
                    keystoreLocation = "/kafka/keystore.p12",
                    credstorePassword = "credstore-password",
                )
            }
        }

        test("the schema registry uses basic auth from NAIS") {
            settingsFrom(requiredEnvironment).kafka.schemaRegistry shouldBe KafkaSchemaRegistrySettings(
                url = "https://registry.example",
                auth = SchemaRegistryAuth.Basic(username = "registry-user", password = "registry-password"),
            )
        }

        test("passwords stay out of logs") {
            assertSoftly(settingsFrom(requiredEnvironment).toString()) {
                this shouldNotContain "db-password"
                this shouldNotContain "valkey-password"
                this shouldNotContain "registry-password"
                this shouldNotContain "credstore-password"
            }
        }
    })

// What NAIS injects in prod-gcp; the cluster's config file supplies the rest.
private val requiredEnvironment = mapOf(
    "ELECTOR_PATH" to "elector-path",
    "ELECTOR_SSE_URL" to "http://elector.example/sse",
    "PDP_SUBSCRIPTION_KEY" to "subscription-key",
    "NAIS_CLUSTER_NAME" to "prod-gcp",
    "NAIS_TOKEN_INTROSPECTION_ENDPOINT" to "http://texas.example/introspect",
    "NAIS_TOKEN_ENDPOINT" to "http://texas.example/token",
    "NAIS_TOKEN_EXCHANGE_ENDPOINT" to "http://texas.example/exchange",
    "NARMESTELEDER_DB_HOST" to "db.example",
    "NARMESTELEDER_DB_PORT" to "5432",
    "NARMESTELEDER_DB_DATABASE" to "narmesteleder",
    "NARMESTELEDER_DB_USERNAME" to "db-user",
    "NARMESTELEDER_DB_PASSWORD" to "db-password",
    "NARMESTELEDER_DB_SSLCERT" to "/certs/cert.pem",
    "NARMESTELEDER_DB_SSLKEY_PK8" to "/certs/key.pk8",
    "NARMESTELEDER_DB_SSLROOTCERT" to "/certs/root.pem",
    "NARMESTELEDER_DB_SSLMODE" to "verify-ca",
    "VALKEY_HOST_FELLES_CACHE" to "valkey.example",
    "VALKEY_PORT_FELLES_CACHE" to "26379",
    "VALKEY_USERNAME_FELLES_CACHE" to "valkey-user",
    "VALKEY_PASSWORD_FELLES_CACHE" to "valkey-password",
    "KAFKA_BROKERS" to "kafka.example:9093",
    "KAFKA_SCHEMA_REGISTRY" to "https://registry.example",
    "KAFKA_SCHEMA_REGISTRY_USER" to "registry-user",
    "KAFKA_SCHEMA_REGISTRY_PASSWORD" to "registry-password",
    "KAFKA_TRUSTSTORE_PATH" to "/kafka/truststore.jks",
    "KAFKA_KEYSTORE_PATH" to "/kafka/keystore.p12",
    "KAFKA_CREDSTORE_PASSWORD" to "credstore-password",
)

private fun settingsFrom(environment: Map<String, String>, overrides: Map<String, String> = emptyMap()) = ApplicationSettings.from(
    applicationConfigWithEnvironment(environment, *configResources("prod-gcp").toTypedArray(), overrides = overrides),
)
