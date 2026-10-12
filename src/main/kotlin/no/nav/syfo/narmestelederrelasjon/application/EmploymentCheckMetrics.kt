package no.nav.syfo.narmestelederrelasjon.application

interface EmploymentCheckMetrics {
    fun recordCheck(outcome: ClaimedCheckOutcome)

    /** The gauges hold database totals, so every pod reports the same values; use max, not sum, in Grafana. */
    fun refresh(stats: EmploymentCheckStats)
}

/** What happened to one claimed check. Unlike [EmploymentCheckOutcome], it also covers checks that were not recorded. */
enum class ClaimedCheckOutcome { VALID, WOULD_REVOKE, FAILED, CLAIM_LOST, TIMEOUT }
