package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.PersonEnrichmentTaskSettings
import no.nav.syfo.platform.config.duration

internal fun ApplicationConfig.narmestelederrelasjonSettings() = NarmestelederrelasjonSettings(
    personEnrichmentTask = PersonEnrichmentTaskSettings(
        pollingInterval = duration("narmestelederrelasjon.personEnrichmentTask.interval"),
    ),
)
