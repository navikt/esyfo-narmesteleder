package no.nav.syfo.narmestelederrelasjon

import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings

data class NarmestelederrelasjonSettings(
    val personEnrichmentTask: PersonEnrichmentTaskSettings,
)
