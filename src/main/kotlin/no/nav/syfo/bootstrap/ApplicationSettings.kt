package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenTaskSettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonEmploymentSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnAccessSettings
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
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings

/** Application settings read from `application.conf`. */
data class ApplicationSettings(
    val database: DatabaseSettings,
    val valkey: ValkeySettings,
    val kafka: KafkaSettings,
    val texas: TexasSettings,
    val aaregSettings: AaregSettings,
    val altinnApiSettings: AltinnApiSettings,
    val altinnAuthorizationSettings: AltinnAuthorizationSettings,
    val altinnAccessSettings: AltinnAccessSettings,
    val dinesykmeldteSettings: DinesykmeldteSettings,
    val dialogportenSettings: DialogportenSettings,
    val eregSettings: EregSettings,
    val pdlSettings: PdlSettings,
    val narmestelederbehovSettings: NarmestelederbehovSettings,
    val narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings,
    val narmestelederrelasjonEmploymentSettings: NarmestelederrelasjonEmploymentSettings,
    val narmestelederrelasjonKafkaSettings: NarmestelederrelasjonKafkaSettings,
    val sykmeldingKafkaSettings: SykmeldingKafkaSettings,
    val dialogportenTaskSettings: DialogportenTaskSettings,
    val maintenanceTaskSettings: MaintenanceTaskSettings,
    val personEnrichmentTaskSettings: PersonEnrichmentTaskSettings,
    val leaderElectionSettings: LeaderElectionSettings,
) {
    companion object {
        fun from(config: ApplicationConfig): ApplicationSettings = ConfigReader.read(config) {
            val altinnBaseUrl = string("altinn.baseUrl")
            ApplicationSettings(
                database = DatabaseSettings(
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
                ),
                valkey = ValkeySettings(
                    host = string("valkey.host"),
                    port = int("valkey.port"),
                    username = string("valkey.username"),
                    password = string("valkey.password"),
                    ssl = flag("valkey.ssl"),
                ),
                kafka = KafkaSettings(
                    brokerUrl = string("kafka.brokers"),
                    schemaRegistry = schemaRegistry(),
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
                ),
                texas = TexasSettings(
                    tokenIntrospectionEndpoint = string("texas.tokenIntrospectionEndpoint"),
                    tokenEndpoint = string("texas.tokenEndpoint"),
                    tokenExchangeEndpoint = string("texas.tokenExchangeEndpoint"),
                    exchangeTargetIsAltinnTilganger = altinnTilgangerExchangeTarget(string("nais.clusterName")),
                    azurePreAuthorizedApps = parsed("texas.azurePreAuthorizedApps", "is not a valid JSON list of applications", emptySet()) {
                        preAuthorizedAppsFromJsonOrNull(it)
                    },
                ),
                aaregSettings = AaregSettings(baseUrl = string("aareg.baseUrl"), scope = string("aareg.scope")),
                altinnApiSettings = AltinnApiSettings(baseUrl = altinnBaseUrl),
                altinnAuthorizationSettings = AltinnAuthorizationSettings(
                    subscriptionKey = string("altinn.authorization.subscriptionKey"),
                ),
                altinnAccessSettings = AltinnAccessSettings(baseUrl = string("altinn.tilganger.baseUrl")),
                dinesykmeldteSettings = DinesykmeldteSettings(
                    baseUrl = string("dinesykmeldte.baseUrl"),
                    scope = string("dinesykmeldte.scope"),
                ),
                dialogportenSettings = DialogportenSettings(baseUrl = altinnBaseUrl),
                eregSettings = EregSettings(baseUrl = string("ereg.baseUrl")),
                pdlSettings = PdlSettings(baseUrl = string("pdl.baseUrl"), scope = string("pdl.scope")),
                narmestelederbehovSettings = NarmestelederbehovSettings(
                    persistLeesahNlBehov = flag("narmestelederbehov.persistLeesahNlBehov"),
                    publicIngressUrl = string("narmestelederbehov.publicIngressUrl"),
                    frontendBaseUrl = string("narmestelederbehov.frontendBaseUrl"),
                    dialogUpdatePollingInterval = duration("narmestelederbehov.dialogUpdatePollingInterval"),
                ),
                narmestelederbehovExpirySettings = NarmestelederbehovExpirySettings(
                    daysAfterTom = long("narmestelederbehov.expiry.daysAfterTom"),
                ),
                narmestelederrelasjonEmploymentSettings = NarmestelederrelasjonEmploymentSettings(
                    employmentCheckEnabled = flag("narmestelederrelasjon.employmentCheck.enabled"),
                    employmentCheckObserveSourceEnabled = flag("narmestelederrelasjon.employmentCheck.observeSourceEnabled"),
                    employmentCheckRevokeEnabled = flag("narmestelederrelasjon.employmentCheck.revokeEnabled"),
                ),
                narmestelederrelasjonKafkaSettings = NarmestelederrelasjonKafkaSettings(
                    persistRegister = flag("narmestelederrelasjon.persistRegister"),
                    pdlConsumerEnabled = flag("narmestelederrelasjon.pdlLeesahConsumerEnabled"),
                ),
                sykmeldingKafkaSettings = SykmeldingKafkaSettings(
                    persistSendtSykmelding = flag("sykmelding.persistSendtSykmelding"),
                ),
                dialogportenTaskSettings = DialogportenTaskSettings(
                    enabled = flag("narmestelederbehov.dialogportenTask.enabled"),
                ),
                maintenanceTaskSettings = MaintenanceTaskSettings(
                    enabled = flag("maintenanceTask.enabled"),
                    interval = duration("maintenanceTask.interval"),
                ),
                personEnrichmentTaskSettings = PersonEnrichmentTaskSettings(
                    enabled = flag("narmestelederrelasjon.personEnrichmentTask.enabled"),
                    pollingInterval = duration("narmestelederrelasjon.personEnrichmentTask.interval"),
                ),
                leaderElectionSettings = LeaderElectionSettings(
                    electorPath = string("leaderElection.path"),
                    electorSseUrl = string("leaderElection.sseUrl"),
                ),
            )
        }
    }
}

private fun ConfigReader.schemaRegistry(): KafkaSchemaRegistrySettings {
    val url = string("kafka.schemaRegistry.url")
    return if (flag("kafka.schemaRegistry.authEnabled")) {
        KafkaSchemaRegistrySettings(url, string("kafka.schemaRegistry.username"), string("kafka.schemaRegistry.password"))
    } else {
        KafkaSchemaRegistrySettings(url, username = null, password = null)
    }
}
