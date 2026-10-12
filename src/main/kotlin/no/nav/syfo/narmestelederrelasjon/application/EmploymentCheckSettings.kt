package no.nav.syfo.narmestelederrelasjon.application

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class EmploymentCheckSettings(
    val batchSize: Int = 50,
    val checkInterval: Duration = 30.seconds,
    val lease: Duration = 15.minutes,
    val seedInterval: Duration = 1.hours,
    /** Per pod and run. That is more than one pod can check in an hour (at most 6 000), so seeding never holds back the first sweep. */
    val seedLimit: Int = 10_000,
) {
    init {
        require(lease > CLAIM_SAFETY_MARGIN) { "lease must exceed CLAIM_SAFETY_MARGIN" }
    }
}

/** A check gives up this long before its lease expires, so it does not overlap with a pod that reclaims the row. */
internal val CLAIM_SAFETY_MARGIN = 30.seconds
