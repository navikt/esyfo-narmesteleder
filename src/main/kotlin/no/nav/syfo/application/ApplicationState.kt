package no.nav.syfo.application

data class HealthState(
    var alive: Boolean = true,
    var ready: Boolean = false,
)

@Deprecated("Use HealthState instead", ReplaceWith("HealthState"))
typealias ApplicationState = HealthState
