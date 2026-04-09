package no.nav.syfo.application.events

import io.ktor.events.EventDefinition

sealed interface LeaderChange {
    object Promoted : LeaderChange
    object Demoted : LeaderChange
    object Unaffected : LeaderChange
}

val LeaderChangeEvent = EventDefinition<LeaderChange>()
