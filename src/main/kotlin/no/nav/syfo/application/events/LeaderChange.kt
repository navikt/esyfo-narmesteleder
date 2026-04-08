package no.nav.syfo.application.events

import io.ktor.events.EventDefinition

sealed interface LeaderChange {
    object ElectedLeader : LeaderChange
    object NotLeader : LeaderChange
}

val LeaderChangeEvent = EventDefinition<LeaderChange>()
