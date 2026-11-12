package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.platform.config.duration
import no.nav.syfo.platform.config.flag

internal fun ApplicationConfig.narmestelederrelasjonSettings() = NarmestelederrelasjonSettings(
    kafka = NarmestelederrelasjonKafkaSettings(
        persistRegister = flag("narmestelederrelasjon.persistRegister"),
        pdlConsumerEnabled = flag("narmestelederrelasjon.pdlLeesahConsumerEnabled"),
    ),
    personEnrichmentTask = PersonEnrichmentTaskSettings(
        enabled = flag("narmestelederrelasjon.personEnrichmentTask.enabled"),
        pollingInterval = duration("narmestelederrelasjon.personEnrichmentTask.interval"),
    ),
)
