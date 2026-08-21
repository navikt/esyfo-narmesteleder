package no.nav.syfo.narmesteleder.api.internal.v1

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val EMPLOYEE_LINEMANAGER_TOTAL = "${METRICS_NS}_employee_linemanager_total"
private const val FILTERED_TAG = "filtered"

private val countEmployeeLinemanagerFiltered: Counter = Counter.builder(EMPLOYEE_LINEMANAGER_TOTAL)
    .description("Counts successful employee line manager requests")
    .tag(FILTERED_TAG, "true")
    .register(METRICS_REGISTRY)

private val countEmployeeLinemanagerUnfiltered: Counter = Counter.builder(EMPLOYEE_LINEMANAGER_TOTAL)
    .description("Counts successful employee line manager requests")
    .tag(FILTERED_TAG, "false")
    .register(METRICS_REGISTRY)

fun countEmployeeLinemanager(filtered: Boolean) {
    if (filtered) {
        countEmployeeLinemanagerFiltered.increment()
    } else {
        countEmployeeLinemanagerUnfiltered.increment()
    }
}
