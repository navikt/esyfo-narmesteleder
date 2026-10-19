package no.nav.syfo.sykmelding.application

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val CREATE_BEHOV_SKIPPED_HAS_ACTIVE_NARMESTELEDER = "${METRICS_NS}_create_behov_skipped_has_active_narmesteleder"
val COUNT_CREATE_BEHOV_SKIPPED_HAS_ACTIVE_NARMESTELEDER: Counter = Counter.builder(CREATE_BEHOV_SKIPPED_HAS_ACTIVE_NARMESTELEDER)
    .description("Counts the number of skipped createBehov due to an active narmesteleder relation")
    .register(METRICS_REGISTRY)
