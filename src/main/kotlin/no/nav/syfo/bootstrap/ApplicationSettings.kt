package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.integration.IntegrationSettings
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings
import no.nav.syfo.organisasjonstilgang.OrganisasjonstilgangSettings
import no.nav.syfo.platform.auth.texas.TexasSettings
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings
import no.nav.syfo.platform.cache.ValkeySettings
import no.nav.syfo.platform.config.ConfigReader
import no.nav.syfo.platform.database.DatabaseSettings
import no.nav.syfo.platform.kafka.KafkaSettings
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings

/** Application settings read from `application.conf`, grouped by the module that owns them. */
data class ApplicationSettings(
    val database: DatabaseSettings,
    val valkey: ValkeySettings,
    val kafka: KafkaSettings,
    val texas: TexasSettings,
    val altinnApi: AltinnApiSettings,
    val leaderElection: LeaderElectionSettings,
    val maintenanceTask: MaintenanceTaskSettings,
    val integrations: IntegrationSettings,
    val organisasjonstilgang: OrganisasjonstilgangSettings,
    val narmestelederbehov: NarmestelederbehovSettings,
    val narmestelederrelasjon: NarmestelederrelasjonSettings,
    val sykmelding: SykmeldingKafkaSettings,
) {
    companion object {
        fun from(config: ApplicationConfig): ApplicationSettings = ConfigReader.read(config) {
            ApplicationSettings(
                database = databaseSettings(),
                valkey = valkeySettings(),
                kafka = kafkaSettings(),
                texas = texasSettings(),
                altinnApi = altinnApiSettings(),
                leaderElection = leaderElectionSettings(),
                maintenanceTask = maintenanceTaskSettings(),
                integrations = integrationSettings(),
                organisasjonstilgang = organisasjonstilgangSettings(),
                narmestelederbehov = narmestelederbehovSettings(),
                narmestelederrelasjon = narmestelederrelasjonSettings(),
                sykmelding = sykmeldingSettings(),
            )
        }
    }
}
