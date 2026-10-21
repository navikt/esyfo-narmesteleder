package no.nav.syfo.platform.scheduling

import io.ktor.events.EventDefinition

sealed interface LeaderChange {
    data object Promoted : LeaderChange
    data object Demoted : LeaderChange
    data object Unaffected : LeaderChange
}

val LeaderChangeEvent = EventDefinition<LeaderChange>()
