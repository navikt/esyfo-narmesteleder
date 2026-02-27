package no.nav.syfo.application.valkey

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

val COUNT_CACHE_HIT_PDL_GET_PERSON: Counter = Counter.builder("${METRICS_NS}_cache_hit_pdl_get_person")
    .description("Counts the number of cache hits when retrieving dine pdl person from Valkey")
    .register(METRICS_REGISTRY)

val COUNT_CACHE_MISS_PDL_GET_PERSON: Counter = Counter.builder("${METRICS_NS}_cache_miss_pdl_get_person")
    .description("Counts the number of cache misses when retrieving dine pdl person from Valkey")
    .register(METRICS_REGISTRY)
