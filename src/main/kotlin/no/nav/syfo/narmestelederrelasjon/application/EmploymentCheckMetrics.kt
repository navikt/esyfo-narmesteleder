package no.nav.syfo.narmestelederrelasjon.application

interface EmploymentCheckMetrics {
    fun countCheck(outcome: CheckOutcome)
    fun countComparison(result: EmploymentComparisonResult)
    fun countObservation(outcome: SourceObservationOutcome)

    /** Gauges are global database totals on every pod; use max, not sum, in Grafana. */
    fun refresh(stats: EmploymentCheckStats)
}
