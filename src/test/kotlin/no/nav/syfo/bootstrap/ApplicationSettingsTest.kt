package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import no.nav.syfo.platform.config.applicationConfigWithEnvironment

class ApplicationSettingsTest :
    FunSpec({
        test("switches that change data or skip error handling are off unless NAIS enables them") {
            assertSoftly(settingsFrom(requiredEnvironment)) {
                kafka.commitOnAllErrors shouldBe false
                narmestelederrelasjon.employmentCheck.employmentCheckRevokeEnabled shouldBe false
            }
        }

        test("startup reports every missing required setting in one error") {
            val failure = shouldThrow<IllegalStateException> { settingsFrom(emptyMap()) }

            assertSoftly(failure.message.orEmpty()) {
                this shouldStartWith "Invalid configuration: "
                this shouldContain "database.password is missing"
                this shouldContain "texas.tokenEndpoint is missing"
                this shouldContain "pdl.baseUrl is missing"
                this shouldContain "narmestelederbehov.dialogporten.publicIngressUrl is missing"
            }
        }

        test("startup reports invalid values without the values") {
            val failure = shouldThrow<IllegalStateException> {
                settingsFrom(
                    requiredEnvironment + mapOf(
                        "DIALOGPORTEN_UPDATE_POLLING_DELAY" to "soon",
                        "DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS" to "sixteen",
                        "AZURE_APP_PRE_AUTHORIZED_APPS" to "secret-client-list",
                    ),
                )
            }

            assertSoftly(failure.message.orEmpty()) {
                this shouldBe "Invalid configuration: " +
                    "texas.azurePreAuthorizedApps is not a valid JSON list of applications, " +
                    "narmestelederbehov.expiry.daysAfterTom is not a whole number, " +
                    "narmestelederbehov.dialogporten.updatePollingInterval is not a valid duration"
                this shouldNotContain "soon"
                this shouldNotContain "sixteen"
                this shouldNotContain "secret-client-list"
            }
        }

        test("the database and Kafka connect with the NAIS certificates and keep the passwords out of logs") {
            val settings = settingsFrom(requiredEnvironment)

            assertSoftly {
                settings.database.jdbcUrl() shouldBe "jdbc:postgresql://db.example:5432/narmesteleder" +
                    "?ssl=on&sslrootcert=/certs/root.pem&sslcert=/certs/cert.pem&sslmode=verify-ca&sslkey=/certs/key.pk8"
                settings.toString() shouldNotContain "db-password"
                settings.toString() shouldNotContain "valkey-password"
                settings.toString() shouldNotContain "registry-password"
                settings.toString() shouldNotContain "credstore-password"
            }
        }
    })

private val requiredEnvironment = mapOf(
    "PUBLIC_INGRESS_URL" to "https://ingress.example",
    "FRONTEND_BASE_URL" to "https://frontend.example",
    "DIALOGPORTEN_TASK_ENABLED" to "true",
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
    "AZURE_APP_PRE_AUTHORIZED_APPS" to """[{"name": "test-cluster:team:app", "clientId": "client-id"}]""",
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
