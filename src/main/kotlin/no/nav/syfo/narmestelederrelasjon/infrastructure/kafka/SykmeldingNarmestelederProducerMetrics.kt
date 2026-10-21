package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import io.micrometer.core.instrument.Counter
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY

const val FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS = "${METRICS_NS}_assign_linemanager_from_empty_form_by_lps"
val COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS: Counter =
    Counter.builder(FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS)
        .description("Counts the number of failed assignment line managers from empty form by LPS")
        .register(METRICS_REGISTRY)

const val FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS = "${METRICS_NS}_revoke_linemanager_from_empty_form_by_lps"
val COUNT_FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS: Counter =
    Counter.builder(FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS)
        .description("Counts the number of failed revoke line managers from empty form by LPS")
        .register(METRICS_REGISTRY)

val COUNT_FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER: Counter =
    Counter.builder(FAILED_REVOKE_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS)
        .description("Counts the number of failed revoke line managers from empty form by LPS")
        .register(METRICS_REGISTRY)

const val FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER =
    "${METRICS_NS}_assign_linemanager_from_empty_form_by_personnel_manager"
val COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER: Counter =
    Counter.builder(FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER)
        .description("Counts the number of failed assigned line managers from empty form by personnel manager")
        .register(METRICS_REGISTRY)
