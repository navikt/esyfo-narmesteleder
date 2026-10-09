package no.nav.syfo.narmestelederrelasjon.application

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class EmploymentCheckSettings(
    val enabled: Boolean = false,
    /** Must be false once live revocation publishing is enabled; our own revocations return on Leesah. */
    val observeSourceEnabled: Boolean = false,
    val batchSize: Int = 50,
    val interval: Duration = 30.seconds,
    val lease: Duration = 15.minutes,
    val seedInterval: Duration = 1.hours,
    val seedLimit: Int = 1000,
) {
    init {
        require(batchSize > 0 && seedLimit > 0) { "Batch size and seed limit must be positive" }
        require(lease.isFinite() && lease > CLAIM_SAFETY_MARGIN) { "Lease must exceed the claim safety margin" }
    }
}

internal val CLAIM_SAFETY_MARGIN = 30.seconds
