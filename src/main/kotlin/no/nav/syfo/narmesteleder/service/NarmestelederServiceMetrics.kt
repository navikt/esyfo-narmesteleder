package no.nav.syfo.narmesteleder.service

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val CREATE_BEHOV_SKIPPED_NO_SICKLEAVE = "${METRICS_NS}_create_behov_skipped_no_active_sickleave"
val COUNT_CREATE_BEHOV_SKIPPED_NO_SICKLEAVE: Counter = Counter.builder(CREATE_BEHOV_SKIPPED_NO_SICKLEAVE)
    .description("Counts the number of skipped createBehov due to no active sickleave")
    .register(METRICS_REGISTRY)

const val CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING = "${METRICS_NS}_create_behov_skipped_has_pre_existing"
val COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING: Counter = Counter.builder(CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING)
    .description("Counts the number of skipped createBehov due to pre-existing entity")
    .register(METRICS_REGISTRY)

const val NL_BEHOV_STORED_AS_ERROR_NO_MAIN_ORGUNIT = "${METRICS_NS}_nl_behov_stored_as_error_no_main_orgunit"
val COUNT_CREATE_BEHOV_STORED_ERROR_NO_MAIN_ORGUNIT: Counter = Counter.builder(NL_BEHOV_STORED_AS_ERROR_NO_MAIN_ORGUNIT)
    .description("Counts the number of nl-behov stored as error due to no main orgunit")
    .register(METRICS_REGISTRY)
