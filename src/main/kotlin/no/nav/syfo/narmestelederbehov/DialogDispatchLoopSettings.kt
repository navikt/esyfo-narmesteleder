package no.nav.syfo.narmestelederbehov

import kotlin.time.Duration

data class DialogDispatchLoopSettings(
    val enabled: Boolean,
    val interval: Duration,
)
