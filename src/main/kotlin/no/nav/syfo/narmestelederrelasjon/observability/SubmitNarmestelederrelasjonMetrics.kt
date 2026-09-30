package no.nav.syfo.narmestelederrelasjon.observability

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource

const val ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS = "${METRICS_NS}_assign_linemanager_from_empty_form_by_lps"
val COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS: Counter =
    Counter.builder(ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS)
        .description("Counts the number of assigned line managers from empty form by LPS")
        .register(METRICS_REGISTRY)

const val ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER =
    "${METRICS_NS}_assign_linemanager_from_empty_form_by_personnel_manager"
val COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER: Counter =
    Counter.builder(ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER)
        .description("Counts the number of assigned line managers from empty form by personnel manager")
        .register(METRICS_REGISTRY)

fun countSubmittedNarmestelederrelasjon(source: RelationSource) = when (source) {
    RelationSource.LPS -> COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS.increment()
    RelationSource.PERSONNEL_MANAGER -> COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER.increment()
}
