package no.nav.syfo.narmesteleder.service

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL =
    "${METRICS_NS}_employee_linemanager_discarded_email_address_total"

private val discardedEmailAddressCounter: Counter = Counter.builder(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL)
    .description("Counts invalid email addresses discarded from employee line manager responses")
    .register(METRICS_REGISTRY)

fun countDiscardedEmployeeLinemanagerEmailAddresses(count: Int) {
    if (count > 0) {
        discardedEmailAddressCounter.increment(count.toDouble())
    }
}
