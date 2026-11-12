package no.nav.syfo.narmestelederrelasjon

import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings

data class NarmestelederrelasjonSettings(
    val kafka: NarmestelederrelasjonKafkaSettings,
    val personEnrichmentTask: PersonEnrichmentTaskSettings,
)
