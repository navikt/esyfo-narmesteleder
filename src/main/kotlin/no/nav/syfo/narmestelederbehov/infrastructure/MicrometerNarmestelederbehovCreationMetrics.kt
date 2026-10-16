package no.nav.syfo.narmestelederbehov.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovCreationMetrics

const val CREATE_BEHOV_SKIPPED_NO_SICKLEAVE = "${METRICS_NS}_create_behov_skipped_no_active_sickleave"
const val CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING = "${METRICS_NS}_create_behov_skipped_has_pre_existing"
const val NL_BEHOV_STORED_AS_ERROR_NO_MAIN_ORGUNIT = "${METRICS_NS}_nl_behov_stored_as_error_no_main_orgunit"
const val NL_BEHOV_STORED_AS_ARBEIDSFORHOLD_NOT_FOUND = "${METRICS_NS}_nl_behov_stored_as_arbeidsforhold_not_found"

class MicrometerNarmestelederbehovCreationMetrics(registry: MeterRegistry = METRICS_REGISTRY) : NarmestelederbehovCreationMetrics {
    private val skippedNoActiveSykmelding: Counter = Counter.builder(CREATE_BEHOV_SKIPPED_NO_SICKLEAVE)
        .description("Counts the number of skipped createBehov due to no active sickleave")
        .register(registry)
    private val skippedAlreadyExists: Counter = Counter.builder(CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING)
        .description("Counts the number of skipped createBehov due to pre-existing entity")
        .register(registry)
    private val storedWithoutMainOrganization: Counter = Counter.builder(NL_BEHOV_STORED_AS_ERROR_NO_MAIN_ORGUNIT)
        .description("Counts the number of nl-behov stored as error due to no main orgunit")
        .register(registry)
    private val storedWithoutEmployment: Counter = Counter.builder(NL_BEHOV_STORED_AS_ARBEIDSFORHOLD_NOT_FOUND)
        .description("Counts the number of nl-behov stored as arbeidsforhold not found")
        .register(registry)

    override fun recordSkippedNoActiveSykmelding() = skippedNoActiveSykmelding.increment()

    override fun recordSkippedAlreadyExists() = skippedAlreadyExists.increment()

    override fun recordStoredWithoutMainOrganization() = storedWithoutMainOrganization.increment()

    override fun recordStoredWithoutEmployment() = storedWithoutEmployment.increment()
}
