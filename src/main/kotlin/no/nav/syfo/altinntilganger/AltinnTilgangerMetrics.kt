package no.nav.syfo.altinntilganger

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val HAS_ALTINN3_RESOURCE = "${METRICS_NS}_has_altinn3_resource"

val COUNT_HAS_ALTINN3_RESOURCE: Counter = Counter.builder(HAS_ALTINN3_RESOURCE)
    .description("Counts the number of validation that pass with altinn3 resource")
    .register(METRICS_REGISTRY)
