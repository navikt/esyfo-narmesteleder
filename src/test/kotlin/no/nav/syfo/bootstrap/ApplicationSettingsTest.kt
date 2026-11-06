package no.nav.syfo.bootstrap

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.syfo.integration.IntegrationSettings
import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.DialogportenSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonEmploymentSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.organisasjonstilgang.OrganisasjonstilgangSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnAccessSettings
import no.nav.syfo.platform.auth.texas.TexasSettings
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings
import no.nav.syfo.platform.cache.ValkeySettings
import no.nav.syfo.platform.config.applicationConfigWithEnvironment
import no.nav.syfo.platform.config.localApplicationConfig
import no.nav.syfo.platform.database.DatabaseSettings
import no.nav.syfo.platform.database.DatabaseSsl
import no.nav.syfo.platform.kafka.KafkaSchemaRegistrySettings
import no.nav.syfo.platform.kafka.KafkaSettings
import no.nav.syfo.platform.kafka.KafkaSslSettings
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ApplicationSettingsTest :
    FunSpec({
        test("application.conf keeps the previous defaults when only required variables are set") {
            val settings = settingsFrom(requiredEnvironment)

            settings shouldBe ApplicationSettings(
                database = DatabaseSettings(
                    host = "db.example",
                    port = "5432",
                    name = "narmesteleder",
                    username = "db-user",
                    password = "db-password",
                    ssl = DatabaseSsl(cert = "/certs/cert.pem", key = "/certs/key.pk8", rootCert = "/certs/root.pem", mode = "verify-ca"),
                ),
                valkey = ValkeySettings(
                    host = "valkey.example",
                    port = 26379,
                    username = "valkey-user",
                    password = "valkey-password",
                    ssl = true,
                ),
                kafka = KafkaSettings(
                    brokerUrl = "kafka.example:9093",
                    schemaRegistry = KafkaSchemaRegistrySettings("https://registry.example", "registry-user", "registry-password"),
                    sslConfig = KafkaSslSettings("/kafka/truststore.jks", "/kafka/keystore.p12", "credstore-password"),
                    commitOnAllErrors = false,
                    shouldConsumeTopics = true,
                ),
                texas = TexasSettings(
                    tokenIntrospectionEndpoint = "http://texas.example/introspect",
                    tokenEndpoint = "http://texas.example/token",
                    tokenExchangeEndpoint = "http://texas.example/exchange",
                    exchangeTargetIsAltinnTilganger = "test-cluster:fager:arbeidsgiver-altinn-tilganger",
                    azurePreAuthorizedApps = setOf("client-id"),
                ),
                altinnApi = AltinnApiSettings(baseUrl = "https://altinn.example"),
                leaderElection = LeaderElectionSettings(
                    electorPath = "elector-path",
                    electorSseUrl = "http://elector.example/sse",
                ),
                maintenanceTask = MaintenanceTaskSettings(enabled = false, interval = 24.hours),
                integrations = IntegrationSettings(
                    aareg = AaregSettings(baseUrl = "https://aareg.example", scope = "aareg-scope"),
                    dinesykmeldte = DinesykmeldteSettings(baseUrl = "https://dinesykmeldte.example", scope = "dinesykmeldte-scope"),
                    ereg = EregSettings(baseUrl = "https://ereg.example"),
                    pdl = PdlSettings(baseUrl = "https://pdl.example", scope = "pdl-scope"),
                ),
                organisasjonstilgang = OrganisasjonstilgangSettings(
                    altinnAuthorization = settings.organisasjonstilgang.altinnAuthorization,
                    altinnTilganger = AltinnAccessSettings(baseUrl = "https://altinn-tilganger.example"),
                ),
                narmestelederbehov = NarmestelederbehovSettings(
                    creation = NarmestelederbehovCreationSettings(persistenceEnabled = true),
                    expiry = NarmestelederbehovExpirySettings(daysAfterTom = 16),
                    dialogporten = DialogportenSettings(
                        baseUrl = "https://altinn.example",
                        tasksEnabled = true,
                        publicIngressUrl = "https://ingress.example",
                        frontendBaseUrl = "https://frontend.example",
                        updatePollingInterval = 5.minutes,
                    ),
                ),
                narmestelederrelasjon = NarmestelederrelasjonSettings(
                    employmentCheck = NarmestelederrelasjonEmploymentSettings(
                        employmentCheckEnabled = false,
                        employmentCheckObserveSourceEnabled = false,
                        employmentCheckRevokeEnabled = false,
                    ),
                    kafka = NarmestelederrelasjonKafkaSettings(persistRegister = false, pdlConsumerEnabled = false),
                    personEnrichmentTask = PersonEnrichmentTaskSettings(enabled = false, pollingInterval = 5.minutes),
                ),
                sykmelding = SykmeldingKafkaSettings(persistSendtSykmelding = false),
            )
            settings.organisasjonstilgang.altinnAuthorization.subscriptionKey shouldBe "subscription-key"
        }

        test("environment variables override every default in application.conf") {
            val environment = requiredEnvironment + mapOf(
                "PERSIST_LEESAH_NL_BEHOV" to "false",
                "DIALOGPORTEN_UPDATE_POLLING_DELAY" to "10m",
                "DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS" to "3",
                "EMPLOYMENT_CHECK_ENABLED" to "true",
                "EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED" to "true",
                "PERSIST_NARMESTELEDER_REGISTER" to "true",
                "PDL_LEESAH_CONSUMER_ENABLED" to "true",
                "PERSIST_SENDT_SYKMELDING" to "true",
                "BEHOV_MAINTENANCE_TASK_ENABLED" to "true",
                "MAINTENANCE_TASK_DELAY" to "1h",
                "PERSON_ENRICHMENT_TASK_ENABLED" to "true",
                "PERSON_ENRICHMENT_TASK_DELAY" to "30s",
            )

            assertSoftly(settingsFrom(environment)) {
                narmestelederbehov.creation.persistenceEnabled shouldBe false
                narmestelederbehov.dialogporten.updatePollingInterval shouldBe 10.minutes
                narmestelederbehov.expiry.daysAfterTom shouldBe 3
                narmestelederrelasjon.employmentCheck.employmentCheckEnabled shouldBe true
                narmestelederrelasjon.employmentCheck.employmentCheckObserveSourceEnabled shouldBe true
                narmestelederrelasjon.kafka.persistRegister shouldBe true
                narmestelederrelasjon.kafka.pdlConsumerEnabled shouldBe true
                sykmelding.persistSendtSykmelding shouldBe true
                maintenanceTask shouldBe MaintenanceTaskSettings(enabled = true, interval = 1.hours)
                narmestelederrelasjon.personEnrichmentTask shouldBe PersonEnrichmentTaskSettings(enabled = true, pollingInterval = 30.seconds)
            }
        }

        test("flags keep the lenient parsing where only a case-insensitive true enables them") {
            val settings = settingsFrom(
                requiredEnvironment + mapOf("EMPLOYMENT_CHECK_ENABLED" to "TRUE", "PERSIST_SENDT_SYKMELDING" to "yes"),
            )

            assertSoftly(settings) {
                narmestelederrelasjon.employmentCheck.employmentCheckEnabled shouldBe true
                sykmelding.persistSendtSykmelding shouldBe false
            }
        }

        test("startup reports every missing required setting in one error") {
            val failure = shouldThrow<IllegalStateException> { settingsFrom(emptyMap()) }

            failure.message shouldBe "Invalid configuration: " +
                "database.host is missing, " +
                "database.port is missing, " +
                "database.name is missing, " +
                "database.username is missing, " +
                "database.password is missing, " +
                "database.ssl.cert is missing, " +
                "database.ssl.key is missing, " +
                "database.ssl.rootCert is missing, " +
                "database.ssl.mode is missing, " +
                "valkey.host is missing, " +
                "valkey.port is missing, " +
                "valkey.username is missing, " +
                "valkey.password is missing, " +
                "kafka.brokers is missing, " +
                "kafka.schemaRegistry.url is missing, " +
                "kafka.schemaRegistry.username is missing, " +
                "kafka.schemaRegistry.password is missing, " +
                "kafka.ssl.truststorePath is missing, " +
                "kafka.ssl.keystorePath is missing, " +
                "kafka.ssl.credstorePassword is missing, " +
                "kafka.consumeTopics is missing, " +
                "texas.tokenIntrospectionEndpoint is missing, " +
                "texas.tokenEndpoint is missing, " +
                "texas.tokenExchangeEndpoint is missing, " +
                "nais.clusterName is missing, " +
                "texas.azurePreAuthorizedApps is missing, " +
                "altinn.baseUrl is missing, " +
                "leaderElection.path is missing, " +
                "leaderElection.sseUrl is missing, " +
                "aareg.baseUrl is missing, " +
                "aareg.scope is missing, " +
                "dinesykmeldte.baseUrl is missing, " +
                "dinesykmeldte.scope is missing, " +
                "ereg.baseUrl is missing, " +
                "pdl.baseUrl is missing, " +
                "pdl.scope is missing, " +
                "altinn.authorization.subscriptionKey is missing, " +
                "altinn.tilganger.baseUrl is missing, " +
                "narmestelederbehov.dialogportenTask.enabled is missing, " +
                "narmestelederbehov.publicIngressUrl is missing, " +
                "narmestelederbehov.frontendBaseUrl is missing"
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
                    "narmestelederbehov.dialogUpdatePollingInterval is not a valid duration"
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

        test("local config connects to the database, Valkey and Kafka without TLS or credentials") {
            val settings = ApplicationSettings.from(localApplicationConfig())

            assertSoftly {
                settings.database.jdbcUrl() shouldBe "jdbc:postgresql://localhost:5432/esyfo-narmesteleder_dev"
                settings.valkey.ssl shouldBe false
                settings.kafka.sslConfig shouldBe null
                settings.kafka.schemaRegistry shouldBe KafkaSchemaRegistrySettings("http://localhost:8081", username = null, password = null)
            }
        }

        test("startup still rejects simultaneous employment revocation and source observation") {
            shouldThrow<IllegalArgumentException> {
                settingsFrom(
                    requiredEnvironment + mapOf(
                        "EMPLOYMENT_CHECK_REVOKE_ENABLED" to "true",
                        "EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED" to "true",
                    ),
                )
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
