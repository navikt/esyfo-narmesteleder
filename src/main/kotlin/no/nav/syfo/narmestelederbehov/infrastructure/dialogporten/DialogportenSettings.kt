package no.nav.syfo.narmestelederbehov.infrastructure.dialogporten

import kotlin.time.Duration

data class DialogportenSettings(
    val baseUrl: String,
    val publicIngressUrl: String,
    val frontendBaseUrl: String,
    val updatePollingInterval: Duration,
)
