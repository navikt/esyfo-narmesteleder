package no.nav.syfo.application.valkey

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

val COUNT_CACHE_HIT_DINE_SYKMELDTE: Counter = Counter.builder("${METRICS_NS}_cache_hit_dine_sykmeldte")
    .description("Counts the number of cache hits when retrieving dine sykmeldte from Valkey")
    .register(METRICS_REGISTRY)

val COUNT_CACHE_MISS_DINE_SYKMELDTE: Counter = Counter.builder("${METRICS_NS}_cache_miss_dine_sykmeldte")
    .description("Counts the number of cache misses when retrieving dine sykmeldte from Valkey")
    .register(METRICS_REGISTRY)
