package no.nav.syfo.platform.scheduling

data class LeaderElectionSettings(
    val electorPath: String,
    val electorSseUrl: String,
)
