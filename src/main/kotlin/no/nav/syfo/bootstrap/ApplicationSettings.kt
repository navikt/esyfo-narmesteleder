package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovExpirySettings
import no.nav.syfo.narmestelederbehov.infrastructure.DialogportenTaskSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonEmploymentSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.platform.config.ConfigReader
import no.nav.syfo.platform.scheduling.LeaderElectionSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings

/** Application behaviour settings read from `application.conf`. */
data class ApplicationSettings(
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
            ApplicationSettings(
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
