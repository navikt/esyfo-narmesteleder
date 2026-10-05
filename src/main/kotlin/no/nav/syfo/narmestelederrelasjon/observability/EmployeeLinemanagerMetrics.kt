package no.nav.syfo.narmestelederrelasjon.observability

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val EMPLOYEE_LINEMANAGER_TOTAL = "${METRICS_NS}_employee_linemanager_total"
const val EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL =
    "${METRICS_NS}_employee_linemanager_discarded_email_address_total"
private const val FILTERED_TAG = "filtered"

private val countEmployeeLinemanagerFiltered: Counter = Counter.builder(EMPLOYEE_LINEMANAGER_TOTAL)
    .description("Counts successful employee line manager requests")
    .tag(FILTERED_TAG, "true")
    .register(METRICS_REGISTRY)

private val countEmployeeLinemanagerUnfiltered: Counter = Counter.builder(EMPLOYEE_LINEMANAGER_TOTAL)
    .description("Counts successful employee line manager requests")
    .tag(FILTERED_TAG, "false")
    .register(METRICS_REGISTRY)

private val discardedEmailAddressCounter: Counter = Counter.builder(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL)
    .description("Counts invalid email addresses discarded from employee line manager responses")
    .register(METRICS_REGISTRY)

fun countEmployeeLinemanager(filtered: Boolean) {
    if (filtered) {
        countEmployeeLinemanagerFiltered.increment()
    } else {
        countEmployeeLinemanagerUnfiltered.increment()
    }
}

fun countDiscardedEmployeeLinemanagerEmailAddresses(count: Int) {
    if (count > 0) {
        discardedEmailAddressCounter.increment(count.toDouble())
    }
}
