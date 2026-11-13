package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.platform.config.applicationConfigWithEnvironment
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
            val failure = shouldThrow<IllegalStateException> { settingsFrom(requiredEnvironment - "PDL_BASE_URL") }

            failure.message shouldBe "Invalid configuration: pdl.baseUrl is missing"
        }

        listOf(
            Triple("DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS", "sixteen", "narmestelederbehov.expiry.daysAfterTom is not a whole number"),
            Triple("DIALOGPORTEN_UPDATE_POLLING_DELAY", "soon", "narmestelederbehov.dialogporten.updatePollingInterval is not a valid duration"),
        ).forEach { (variable, value, problem) ->
            test("startup reports an invalid $variable without the value") {
                val failure = shouldThrow<IllegalStateException> { settingsFrom(requiredEnvironment + (variable to value)) }

                assertSoftly(failure.message.orEmpty()) {
                    this shouldBe "Invalid configuration: $problem"
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

private val requiredEnvironment = mapOf(
    "PUBLIC_INGRESS_URL" to "https://ingress.example",
    "FRONTEND_BASE_URL" to "https://frontend.example",
    "ELECTOR_PATH" to "elector-path",
    "ELECTOR_SSE_URL" to "http://elector.example/sse",
    "AAREG_BASE_URL" to "https://aareg.example",
    "AAREG_SCOPE" to "aareg-scope",
    "ALTINN_3_BASE_URL" to "https://altinn.example",
    "PDP_SUBSCRIPTION_KEY" to "subscription-key",
    "ALTINN_TILGANGER_BASE_URL" to "https://altinn-tilganger.example",
    "DINESYMELDTE_BASEURL" to "https://dinesykmeldte.example",
    "DINESYMELDTE_SCOPE" to "dinesykmeldte-scope",
    "EREG_BASE_URL" to "https://ereg.example",
    "PDL_BASE_URL" to "https://pdl.example",
    "PDL_SCOPE" to "pdl-scope",
    "NAIS_CLUSTER_NAME" to "test-cluster",
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
    "CONSUME_KAFKA_TOPICS" to "true",
    "KAFKA_SCHEMA_REGISTRY" to "https://registry.example",
    "KAFKA_SCHEMA_REGISTRY_USER" to "registry-user",
    "KAFKA_SCHEMA_REGISTRY_PASSWORD" to "registry-password",
    "KAFKA_TRUSTSTORE_PATH" to "/kafka/truststore.jks",
    "KAFKA_KEYSTORE_PATH" to "/kafka/keystore.p12",
    "KAFKA_CREDSTORE_PASSWORD" to "credstore-password",
)

private fun settingsFrom(environment: Map<String, String>) = ApplicationSettings.from(applicationConfigWithEnvironment(environment))
