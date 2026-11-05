package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlin.time.Duration

data class PersonEnrichmentTaskSettings(
    val enabled: Boolean,
    val pollingInterval: Duration,
)
