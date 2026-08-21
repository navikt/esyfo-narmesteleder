package no.nav.syfo.sykmelding.service

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val NARMESTELEDER_BRUDD_FROM_SENDT_SYKMELDING = "${METRICS_NS}_narmesteleder_brudd_from_sendt_sykmelding"
val COUNT_NARMESTELEDER_BRUDD_FROM_SENDT_SYKMELDING: Counter =
    Counter.builder(NARMESTELEDER_BRUDD_FROM_SENDT_SYKMELDING)
        .description("Counts tracked line manager relation revokes from sent sick leave messages")
        .register(METRICS_REGISTRY)
