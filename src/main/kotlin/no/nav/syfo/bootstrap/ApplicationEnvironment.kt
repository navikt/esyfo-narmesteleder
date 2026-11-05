package no.nav.syfo.bootstrap

import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenTaskSettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerSettings
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.altinn.Altinn3Settings
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.config.getEnvVar
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

interface Environment {
    val database: DatabaseEnvironment
    val texas: TexasEnvironment
    val valkeyEnvironment: ValkeyEnvironment
    val kafka: KafkaEnvironment
    val aaregSettings: AaregSettings
    val altinn3Settings: Altinn3Settings
    val altinnAuthorizationSettings: AltinnAuthorizationSettings
    val altinnTilgangerSettings: AltinnTilgangerSettings
    val dinesykmeldteSettings: DinesykmeldteSettings
    val dialogportenSettings: DialogportenSettings
    val eregSettings: EregSettings
    val pdlSettings: PdlSettings
    val narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings
    val dialogportenTaskSettings: DialogportenTaskSettings
    val maintenanceTaskSettings: MaintenanceTaskSettings
    val personEnrichmentTaskSettings: PersonEnrichmentTaskSettings
    val leaderElectionSettings: LeaderElectionSettings
    val otherProperties: OtherEnvironmentProperties
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
    override val altinn3Settings: Altinn3Settings = Altinn3Settings(
        baseUrl = getEnvVar("ALTINN_3_BASE_URL"),
    ),
    override val altinnAuthorizationSettings: AltinnAuthorizationSettings = AltinnAuthorizationSettings(
        subscriptionKey = getEnvVar("PDP_SUBSCRIPTION_KEY"),
    ),
    override val altinnTilgangerSettings: AltinnTilgangerSettings = AltinnTilgangerSettings(
        baseUrl = getEnvVar("ALTINN_TILGANGER_BASE_URL"),
    ),
    override val dinesykmeldteSettings: DinesykmeldteSettings = DinesykmeldteSettings(
        baseUrl = getEnvVar("DINESYMELDTE_BASEURL"),
        scope = getEnvVar("DINESYMELDTE_SCOPE"),
    ),
    override val dialogportenSettings: DialogportenSettings = DialogportenSettings(
        baseUrl = altinn3Settings.baseUrl,
    ),
    override val eregSettings: EregSettings = EregSettings(
        baseUrl = getEnvVar("EREG_BASE_URL"),
    ),
    override val pdlSettings: PdlSettings = PdlSettings(
        baseUrl = getEnvVar("PDL_BASE_URL"),
        scope = getEnvVar("PDL_SCOPE"),
    ),
    override val narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings = NarmestelederbehovExpirySettings(
        daysAfterTom = getEnvVar("DAYS_AFTER_TOM_TO_EXPIRE_BEHOVS", "16").toLong(),
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
    override val otherProperties: OtherEnvironmentProperties = OtherEnvironmentProperties.createFromEnvVars(),
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
    override val altinn3Settings: Altinn3Settings = Altinn3Settings(
        baseUrl = "http://localhost:8080/dialogporten",
    ),
    override val altinnAuthorizationSettings: AltinnAuthorizationSettings = AltinnAuthorizationSettings(
        subscriptionKey = "secret-key",
    ),
    override val altinnTilgangerSettings: AltinnTilgangerSettings = AltinnTilgangerSettings(
        baseUrl = "https://altinn-tilganger-api.dev.intern.nav.no",
    ),
    override val dinesykmeldteSettings: DinesykmeldteSettings = DinesykmeldteSettings(
        baseUrl = "",
        scope = "",
    ),
    override val dialogportenSettings: DialogportenSettings = DialogportenSettings(
        baseUrl = altinn3Settings.baseUrl,
    ),
    override val eregSettings: EregSettings = EregSettings(
        baseUrl = "https://ereg-test.intern.nav.no",
    ),
    override val pdlSettings: PdlSettings = PdlSettings(
        baseUrl = "https://pdl-api.dev.intern.nav.no",
        scope = "pdl",
    ),
    override val narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings = NarmestelederbehovExpirySettings(
        daysAfterTom = 0,
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
    override val otherProperties: OtherEnvironmentProperties = OtherEnvironmentProperties.createForLocal(),
) : Environment
