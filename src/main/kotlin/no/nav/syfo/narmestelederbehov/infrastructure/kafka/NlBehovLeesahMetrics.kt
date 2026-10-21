package no.nav.syfo.narmestelederbehov.infrastructure.kafka

import io.micrometer.core.instrument.Counter
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY

const val CREATE_LINEMANAGER_REQUIREMENT = "${METRICS_NS}_create_linemanager_requirement"
val COUNT_CREATE_LINEMANAGER_REQUIREMENT: Counter = Counter.builder(CREATE_LINEMANAGER_REQUIREMENT)
    .description("Counts the number of created requirements")
    .register(METRICS_REGISTRY)
