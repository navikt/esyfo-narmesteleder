package no.nav.syfo.narmestelederrelasjon.observability

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val LOOKUP_NARMESTELEDER = "${METRICS_NS}_lookup_narmesteleder"
val COUNT_LOOKUP_NARMESTELEDER: Counter = Counter.builder(LOOKUP_NARMESTELEDER)
    .description("Counts line manager lookups by org/sykmeldt fnr")
    .register(METRICS_REGISTRY)
