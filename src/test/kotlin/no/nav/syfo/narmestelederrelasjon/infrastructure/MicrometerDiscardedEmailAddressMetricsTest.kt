package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import no.nav.syfo.application.metric.METRICS_REGISTRY

class MicrometerDiscardedEmailAddressMetricsTest :
    FunSpec({
        test("employee line manager metrics increment only their own counter") {
            val employeeBefore = count(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL)
            val lookupBefore = count(LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL)
            MicrometerDiscardedEmailAddressMetrics.employeeLinemanager().record(3)
            count(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL) shouldBeExactly employeeBefore + 3.0
            count(LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL) shouldBeExactly lookupBefore
        }

        test("lookup metrics increment only their own counter") {
            val employeeBefore = count(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL)
            val lookupBefore = count(LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL)
            MicrometerDiscardedEmailAddressMetrics.lookupNarmesteleder().record(2)
            count(LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL) shouldBeExactly lookupBefore + 2.0
            count(EMPLOYEE_LINEMANAGER_DISCARDED_EMAIL_ADDRESS_TOTAL) shouldBeExactly employeeBefore
        }

        test("does not increment the counter when nothing was discarded") {
            val before = count(LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL)
            MicrometerDiscardedEmailAddressMetrics.lookupNarmesteleder().record(0)
            count(LOOKUP_NARMESTELEDER_DISCARDED_EMAIL_ADDRESS_TOTAL) shouldBeExactly before
        }
    })

private fun count(name: String): Double = METRICS_REGISTRY
    .find(name)
    .counter()
    ?.count() ?: 0.0
