package no.nav.syfo.narmestelederrelasjon.application

interface EmploymentCheckMetrics {
    fun recordCheck(outcome: ClaimedCheckOutcome)
    fun recordComparison(result: EmploymentComparisonResult)
    fun recordObservation(outcome: SourceObservationOutcome)

    /** The gauges hold database totals, so every pod reports the same values; use max, not sum, in Grafana. */
    fun refresh(stats: EmploymentCheckStats)
}

/** What happened to one claimed check. Unlike [EmploymentCheckOutcome], it also covers checks that were not recorded. */
enum class ClaimedCheckOutcome { VALID, WOULD_REVOKE, REVOKED, STALE, FAILED, CLAIM_LOST, TIMEOUT }

/** Whether the shadow check agrees with team sykmelding's employment-ended revocation («samsvar» or «bare dem» in #473). */
enum class EmploymentComparisonResult { AGREE, DISAGREE }

enum class SourceObservationOutcome { RECORDED, ALREADY_RECORDED, IGNORED_STALE, FAILED }
