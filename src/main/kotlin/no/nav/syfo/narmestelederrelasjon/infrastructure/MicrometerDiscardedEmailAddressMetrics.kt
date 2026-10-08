package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.DiscardedEmailAddressMetrics

const val EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL =
    "${METRICS_NS}_employee_linemanager_discarded_email_address_total"

const val LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL =
    "${METRICS_NS}_lookup_narmesteleder_discarded_email_address_total"

class MicrometerDiscardedEmailAddressMetrics private constructor(
    private val counter: Counter,
) : DiscardedEmailAddressMetrics {
    override fun record(count: Int) {
        if (count > 0) {
            counter.increment(count.toDouble())
        }
    }

    companion object {
        fun employeeLinemanager() = MicrometerDiscardedEmailAddressMetrics(
            Counter.builder(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL)
                .description("Counts invalid email addresses discarded from employee line manager responses")
                .register(METRICS_REGISTRY),
        )

        fun lookupNarmesteleder() = MicrometerDiscardedEmailAddressMetrics(
            Counter.builder(LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL)
                .description("Counts invalid email addresses discarded from narmesteleder lookup responses")
                .register(METRICS_REGISTRY),
        )
    }
}
