package no.nav.syfo.bootstrap

import kotlin.time.Duration

data class MaintenanceTaskSettings(
    val enabled: Boolean,
    val interval: Duration,
)
