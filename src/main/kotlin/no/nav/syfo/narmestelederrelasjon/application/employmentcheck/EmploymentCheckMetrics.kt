package no.nav.syfo.narmestelederrelasjon.application.employmentcheck

interface EmploymentCheckMetrics {
    fun recordCheck(outcome: ClaimedCheckOutcome)

    /** The gauge holds a database total, so every pod reports the same value; use max, not sum, in Grafana. */
    fun refresh(stats: EmploymentCheckStats)
}

/** What happened to one claimed check. Unlike [EmploymentCheckOutcome], it also covers checks that were not recorded. */
enum class ClaimedCheckOutcome { VALID, REVOKED, STALE, FAILED, CLAIM_LOST, TIMEOUT }
