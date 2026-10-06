package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import no.nav.syfo.application.metric.METRICS_REGISTRY

class MicrometerDiscardedEmailAddressMetricsTest :
    FunSpec({
        val metrics = MicrometerDiscardedEmailAddressMetrics()

        test("increments the counter by the discarded count") {
            val before = discardedCount()
            metrics.record(3)
            discardedCount() shouldBeExactly before + 3.0
        }

        test("does not increment the counter when nothing was discarded") {
            val before = discardedCount()
            metrics.record(0)
            discardedCount() shouldBeExactly before
        }
    })

private fun discardedCount(): Double = METRICS_REGISTRY
    .find(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL)
    .counter()
    ?.count() ?: 0.0
