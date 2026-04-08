package no.nav.syfo.narmesteleder.service

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val NARMESTELEDER_REGISTER_UPSERTED = "${METRICS_NS}_narmesteleder_register_upserted"
val COUNT_NARMESTELEDER_REGISTER_UPSERTED: Counter = Counter.builder(NARMESTELEDER_REGISTER_UPSERTED)
    .description("Counts the number of leesah records written to the narmesteleder register")
    .register(METRICS_REGISTRY)

const val NARMESTELEDER_REGISTER_INVALID_MESSAGE = "${METRICS_NS}_narmesteleder_register_invalid_message"
val COUNT_NARMESTELEDER_REGISTER_INVALID_MESSAGE: Counter = Counter.builder(NARMESTELEDER_REGISTER_INVALID_MESSAGE)
    .description("Counts the number of invalid leesah records skipped during register replay")
    .register(METRICS_REGISTRY)
