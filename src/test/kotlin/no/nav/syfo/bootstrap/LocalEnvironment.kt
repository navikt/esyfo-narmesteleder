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
import no.nav.syfo.platform.config.localApplicationConfig
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.kafka.createForLocal
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings

/** Settings for local runs and tests. Production always uses [NaisEnvironment]. */
data class LocalEnvironment(
    private val settings: ApplicationSettings = ApplicationSettings.from(localApplicationConfig()),
    override val database: DatabaseEnvironment = settings.database,
    override val texas: TexasEnvironment = settings.texas,
    override val valkeyEnvironment: ValkeyEnvironment = settings.valkey,
    override val kafka: KafkaEnvironment = KafkaEnvironment.createForLocal(),
    override val aaregSettings: AaregSettings = settings.aaregSettings,
    override val altinnApiSettings: AltinnApiSettings = settings.altinnApiSettings,
    override val altinnAuthorizationSettings: AltinnAuthorizationSettings = settings.altinnAuthorizationSettings,
    override val altinnAccessSettings: AltinnAccessSettings = settings.altinnAccessSettings,
    override val dinesykmeldteSettings: DinesykmeldteSettings = settings.dinesykmeldteSettings,
    override val dialogportenSettings: DialogportenSettings = settings.dialogportenSettings,
    override val eregSettings: EregSettings = settings.eregSettings,
    override val pdlSettings: PdlSettings = settings.pdlSettings,
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
