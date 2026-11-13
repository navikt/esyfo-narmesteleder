package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlin.time.Duration

data class PersonEnrichmentTaskSettings(
    val pollingInterval: Duration,
)
