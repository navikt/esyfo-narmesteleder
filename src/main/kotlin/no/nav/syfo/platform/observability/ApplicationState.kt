package no.nav.syfo.platform.observability

data class ApplicationState(
    var alive: Boolean = true,
    var ready: Boolean = false
)
