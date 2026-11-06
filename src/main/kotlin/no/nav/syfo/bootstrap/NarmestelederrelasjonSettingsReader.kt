package no.nav.syfo.bootstrap

import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonEmploymentSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.platform.config.ConfigReader

internal fun ConfigReader.narmestelederrelasjonSettings() = NarmestelederrelasjonSettings(
    employmentCheck = NarmestelederrelasjonEmploymentSettings(
        employmentCheckEnabled = flag("narmestelederrelasjon.employmentCheck.enabled"),
        employmentCheckObserveSourceEnabled = flag("narmestelederrelasjon.employmentCheck.observeSourceEnabled"),
        employmentCheckRevokeEnabled = flag("narmestelederrelasjon.employmentCheck.revokeEnabled"),
    ),
    kafka = NarmestelederrelasjonKafkaSettings(
        persistRegister = flag("narmestelederrelasjon.persistRegister"),
        pdlConsumerEnabled = flag("narmestelederrelasjon.pdlLeesahConsumerEnabled"),
    ),
    personEnrichmentTask = PersonEnrichmentTaskSettings(
        enabled = flag("narmestelederrelasjon.personEnrichmentTask.enabled"),
        pollingInterval = duration("narmestelederrelasjon.personEnrichmentTask.interval"),
    ),
)
