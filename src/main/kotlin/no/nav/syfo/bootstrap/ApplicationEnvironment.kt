package no.nav.syfo.bootstrap

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
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.config.getEnvVar
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings

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
    val narmestelederrelasjonEmploymentSettings: NarmestelederrelasjonEmploymentSettings
    val narmestelederrelasjonKafkaSettings: NarmestelederrelasjonKafkaSettings
    val sykmeldingKafkaSettings: SykmeldingKafkaSettings
    val dialogportenTaskSettings: DialogportenTaskSettings
    val maintenanceTaskSettings: MaintenanceTaskSettings
    val personEnrichmentTaskSettings: PersonEnrichmentTaskSettings
    val leaderElectionSettings: LeaderElectionSettings
}

data class NaisEnvironment(
    private val settings: ApplicationSettings,
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
    override val narmestelederbehovSettings: NarmestelederbehovSettings = settings.narmestelederbehovSettings,
    override val narmestelederbehovExpirySettings: NarmestelederbehovExpirySettings = settings.narmestelederbehovExpirySettings,
    override val narmestelederrelasjonEmploymentSettings: NarmestelederrelasjonEmploymentSettings = settings.narmestelederrelasjonEmploymentSettings,
    override val narmestelederrelasjonKafkaSettings: NarmestelederrelasjonKafkaSettings = settings.narmestelederrelasjonKafkaSettings,
    override val sykmeldingKafkaSettings: SykmeldingKafkaSettings = settings.sykmeldingKafkaSettings,
    override val dialogportenTaskSettings: DialogportenTaskSettings = settings.dialogportenTaskSettings,
    override val maintenanceTaskSettings: MaintenanceTaskSettings = settings.maintenanceTaskSettings,
    override val personEnrichmentTaskSettings: PersonEnrichmentTaskSettings = settings.personEnrichmentTaskSettings,
    override val leaderElectionSettings: LeaderElectionSettings = settings.leaderElectionSettings,
) : Environment
