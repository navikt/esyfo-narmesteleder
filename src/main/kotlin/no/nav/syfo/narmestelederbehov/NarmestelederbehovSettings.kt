package no.nav.syfo.narmestelederbehov

import kotlin.time.Duration

data class NarmestelederbehovSettings(
    val persistLeesahNlBehov: Boolean,
    val publicIngressUrl: String,
    val frontendBaseUrl: String,
    val dialogUpdatePollingInterval: Duration,
)
