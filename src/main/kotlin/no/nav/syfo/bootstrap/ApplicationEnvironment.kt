package no.nav.syfo.bootstrap

import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenTaskSettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnAccessSettings
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.config.getEnvVar
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

interface Environment {
    val database: DatabaseEnvironment
    val texas: TexasEnvironment
    val valkeyEnvironment: ValkeyEnvironment
    val kafka: KafkaEnvironment
    val aaregSettings: AaregSettings
    val altinnApiSettings: AltinnApiSettings
    val altinnAuthorizationSettings: AltinnAuthorizationSettings
    val altinnAccessSettings: AltinnAccessSettings
    val dinesykmeldteSettings: DinesykmeldteSettings
    val dialogportenSettings: DialogportenSettings
    val eregSettings: EregSettings
    val pdlSettings: PdlSettings
    val narmestelederbehovSettings: NarmestelederbehovSettings
    val narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings
    val narmestelederrelasjonSettings: NarmestelederrelasjonSettings
    val narmestelederrelasjonKafkaSettings: NarmestelederrelasjonKafkaSettings
    val sykmeldingKafkaSettings: SykmeldingKafkaSettings
    val dialogportenTaskSettings: DialogportenTaskSettings
    val maintenanceTaskSettings: MaintenanceTaskSettings
    val personEnrichmentTaskSettings: PersonEnrichmentTaskSettings
    val leaderElectionSettings: LeaderElectionSettings
}

data class NaisEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createFromEnvVars(),
    override val texas: TexasEnvironment = TexasEnvironment.createFromEnvVars(),
    override val valkeyEnvironment: ValkeyEnvironment = ValkeyEnvironment.createFromEnvVars(),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createFromEnvVars(),
    override val aaregSettings: AaregSettings = AaregSettings(
        baseUrl = getEnvVar("AAREG_BASE_URL"),
        scope = getEnvVar("AAREG_SCOPE"),
    ),
    override val altinnApiSettings: AltinnApiSettings = AltinnApiSettings(
        baseUrl = getEnvVar("ALTINN_3_BASE_URL"),
    ),
    override val altinnAuthorizationSettings: AltinnAuthorizationSettings = AltinnAuthorizationSettings(
        subscriptionKey = getEnvVar("PDP_SUBSCRIPTION_KEY"),
    ),
    override val altinnAccessSettings: AltinnAccessSettings = AltinnAccessSettings(
        baseUrl = getEnvVar("ALTINN_TILGANGER_BASE_URL"),
    ),
    override val dinesykmeldteSettings: DinesykmeldteSettings = DinesykmeldteSettings(
        baseUrl = getEnvVar("DINESYMELDTE_BASEURL"),
        scope = getEnvVar("DINESYMELDTE_SCOPE"),
    ),
    override val dialogportenSettings: DialogportenSettings = DialogportenSettings(
        baseUrl = altinnApiSettings.baseUrl,
    ),
    override val eregSettings: EregSettings = EregSettings(
        baseUrl = getEnvVar("EREG_BASE_URL"),
    ),
    override val pdlSettings: PdlSettings = PdlSettings(
        baseUrl = getEnvVar("PDL_BASE_URL"),
        scope = getEnvVar("PDL_SCOPE"),
    ),
    override val narmestelederbehovSettings: NarmestelederbehovSettings = NarmestelederbehovSettings(
        persistLeesahNlBehov = getEnvVar("PERSIST_LEESAH_NL_BEHOV", "true").toBoolean(),
        publicIngressUrl = getEnvVar("PUBLIC_INGRESS_URL"),
        frontendBaseUrl = getEnvVar("FRONTEND_BASE_URL"),
        dialogUpdatePollingInterval = Duration.parse(getEnvVar("DIALOGPORTEN_UPDATE_POLLING_DELAY", "5m")),
    ),
    override val narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings = NarmestelederbehovExpirySettings(
        daysAfterTom = getEnvVar("DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS", "16").toLong(),
    ),
    override val narmestelederrelasjonSettings: NarmestelederrelasjonSettings = NarmestelederrelasjonSettings(
        employmentCheckEnabled = getEnvVar("EMPLOYMENT_CHECK_ENABLED", "false").toBoolean(),
        employmentCheckObserveSourceEnabled = getEnvVar("EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED", "false").toBoolean(),
        employmentCheckRevokeEnabled = getEnvVar("EMPLOYMENT_CHECK_REVOKE_ENABLED", "false").toBoolean(),
    ),
    override val narmestelederrelasjonKafkaSettings: NarmestelederrelasjonKafkaSettings = NarmestelederrelasjonKafkaSettings(
        persistRegister = getEnvVar("PERSIST_NARMESTELEDER_REGISTER", "false").toBoolean(),
        pdlConsumerEnabled = getEnvVar("PDL_LEESAH_CONSUMER_ENABLED", "false").toBoolean(),
    ),
    override val sykmeldingKafkaSettings: SykmeldingKafkaSettings = SykmeldingKafkaSettings(
        persistSendtSykmelding = getEnvVar("PERSIST_SENDT_SYKMELDING", "false").toBoolean(),
    ),
    override val dialogportenTaskSettings: DialogportenTaskSettings = DialogportenTaskSettings(
        enabled = getEnvVar("DIALOGPORTEN_TASK_ENABLED").toBoolean(),
    ),
    override val maintenanceTaskSettings: MaintenanceTaskSettings = MaintenanceTaskSettings(
        enabled = getEnvVar("BEHOV_MAINTENANCE_TASK_ENABLED", "false").toBoolean(),
        interval = Duration.parse(getEnvVar("MAINTENANCE_TASK_DELAY", "24h")),
    ),
    override val personEnrichmentTaskSettings: PersonEnrichmentTaskSettings = PersonEnrichmentTaskSettings(
        enabled = getEnvVar("PERSON_ENRICHMENT_TASK_ENABLED", "false").toBoolean(),
        pollingInterval = Duration.parse(getEnvVar("PERSON_ENRICHMENT_TASK_DELAY", "5m")),
    ),
    override val leaderElectionSettings: LeaderElectionSettings = LeaderElectionSettings(
        electorPath = getEnvVar("ELECTOR_PATH"),
        electorSseUrl = getEnvVar("ELECTOR_SSE_URL"),
    ),
) : Environment

fun isLocalEnv(): Boolean = getEnvVar("NAIS_CLUSTER_NAME", "local") == "local"

fun loadEnvironment(isLocal: Boolean): Environment = if (isLocal) LocalEnvironment() else NaisEnvironment()

data class LocalEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createForLocal(),
    override val texas: TexasEnvironment = TexasEnvironment.createForLocal(),
    override val valkeyEnvironment: ValkeyEnvironment = ValkeyEnvironment.createForLocal(),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createForLocal(),
    override val aaregSettings: AaregSettings = AaregSettings(
        baseUrl = "",
        scope = "aareg",
    ),
    override val altinnApiSettings: AltinnApiSettings = AltinnApiSettings(
        baseUrl = "http://localhost:8080/dialogporten",
    ),
    override val altinnAuthorizationSettings: AltinnAuthorizationSettings = AltinnAuthorizationSettings(
        subscriptionKey = "secret-key",
    ),
    override val altinnAccessSettings: AltinnAccessSettings = AltinnAccessSettings(
        baseUrl = "https://altinn-tilganger-api.dev.intern.nav.no",
    ),
    override val dinesykmeldteSettings: DinesykmeldteSettings = DinesykmeldteSettings(
        baseUrl = "",
        scope = "",
    ),
    override val dialogportenSettings: DialogportenSettings = DialogportenSettings(
        baseUrl = altinnApiSettings.baseUrl,
    ),
    override val eregSettings: EregSettings = EregSettings(
        baseUrl = "https://ereg-test.intern.nav.no",
    ),
    override val pdlSettings: PdlSettings = PdlSettings(
        baseUrl = "https://pdl-api.dev.intern.nav.no",
        scope = "pdl",
    ),
    override val narmestelederbehovSettings: NarmestelederbehovSettings = NarmestelederbehovSettings(
        persistLeesahNlBehov = true,
        publicIngressUrl = "http://localhost:8080",
        frontendBaseUrl = "http://localhost:3000",
        dialogUpdatePollingInterval = 30.seconds,
    ),
    override val narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings = NarmestelederbehovExpirySettings(
        daysAfterTom = 0,
    ),
    override val narmestelederrelasjonSettings: NarmestelederrelasjonSettings = NarmestelederrelasjonSettings(
        employmentCheckEnabled = false,
        employmentCheckObserveSourceEnabled = false,
        employmentCheckRevokeEnabled = false,
    ),
    override val narmestelederrelasjonKafkaSettings: NarmestelederrelasjonKafkaSettings = NarmestelederrelasjonKafkaSettings(
        persistRegister = true,
        pdlConsumerEnabled = true,
    ),
    override val sykmeldingKafkaSettings: SykmeldingKafkaSettings = SykmeldingKafkaSettings(
        persistSendtSykmelding = true,
    ),
    override val dialogportenTaskSettings: DialogportenTaskSettings = DialogportenTaskSettings(
        enabled = true,
    ),
    override val maintenanceTaskSettings: MaintenanceTaskSettings = MaintenanceTaskSettings(
        enabled = true,
        interval = 1.minutes,
    ),
    override val personEnrichmentTaskSettings: PersonEnrichmentTaskSettings = PersonEnrichmentTaskSettings(
        enabled = true,
        pollingInterval = 1.minutes,
    ),
    override val leaderElectionSettings: LeaderElectionSettings = LeaderElectionSettings(
        electorPath = "esyfo-narmesteleder",
        electorSseUrl = "not.applicable",
    ),
) : Environment
