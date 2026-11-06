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
import no.nav.syfo.platform.auth.texas.createForLocal
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.cache.createForLocal
import no.nav.syfo.platform.config.localApplicationConfig
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.database.createForLocal
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.kafka.createForLocal
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings

/** Settings for local runs and tests. Production always uses [NaisEnvironment]. */
data class LocalEnvironment(
    private val settings: ApplicationSettings = ApplicationSettings.from(localApplicationConfig()),
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
