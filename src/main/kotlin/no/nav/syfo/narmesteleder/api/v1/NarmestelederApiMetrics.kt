package no.nav.syfo.narmesteleder.api.v1

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val REVOKE_LINEMANAGER_BY_LPS = "${METRICS_NS}_revoke_linemanager_by_lps"
val COUNT_REVOKE_LINEMANAGER_BY_LPS: Counter = Counter.builder(REVOKE_LINEMANAGER_BY_LPS)
    .description("Counts the number of revocations performed by LPS")
    .register(METRICS_REGISTRY)

const val REVOKE_LINEMANAGER_BY_PERSONNEL_MANAGER = "${METRICS_NS}_revoke_linemanager_by_personnel_manager"
val COUNT_REVOKE_LINEMANAGER_BY_PERSONNEL_MANAGER: Counter = Counter.builder(REVOKE_LINEMANAGER_BY_PERSONNEL_MANAGER)
    .description("Counts the number of revocations performed by personnel manager")
    .register(METRICS_REGISTRY)

const val FULFILL_LINEMANAGER_REQUIREMENT_BY_LPS = "${METRICS_NS}_fulfill_linemanager_requirement_by_lps"
val COUNT_FULFILL_LINEMANAGER_REQUIREMENT_BY_LPS: Counter = Counter.builder(FULFILL_LINEMANAGER_REQUIREMENT_BY_LPS)
    .description("Counts the number of fulfilled requirements performed by LPS")
    .register(METRICS_REGISTRY)

const val FULFILL_LINEMANAGER_BY_PERSONNEL_MANAGER =
    "${METRICS_NS}_fulfill_linemanager_requirement_by_personnel_manager"
val COUNT_FULFILL_LINEMANAGER_BY_PERSONNEL_MANAGER: Counter = Counter.builder(FULFILL_LINEMANAGER_BY_PERSONNEL_MANAGER)
    .description("Counts the number of fulfilled requirements performed by personnel manager")
    .register(METRICS_REGISTRY)

const val FULFILL_LINEMANAGER_BY_LEGACY_SYSTEM = "${METRICS_NS}_fulfill_linemanager_requirement_by_legacy_system"
val COUNT_FULFILL_LINEMANAGER_BY_LEGACY_SYSTEM: Counter = Counter.builder(FULFILL_LINEMANAGER_BY_LEGACY_SYSTEM)
    .description("Counts the number of fulfilled requirements performed by legacy system")
    .register(METRICS_REGISTRY)

const val CREATE_LINEMANAGER_REQUIREMENT = "${METRICS_NS}_create_linemanager_requirement"
val COUNT_CREATE_LINEMANAGER_REQUIREMENT: Counter = Counter.builder(CREATE_LINEMANAGER_REQUIREMENT)
    .description("Counts the number of created requirements")
    .register(METRICS_REGISTRY)

const val ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS = "${METRICS_NS}_assign_linemanager_from_empty_form_by_lps"
val COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS: Counter =
    Counter.builder(ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS)
        .description("Counts the number of assigned line managers from empty form by LPS")
        .register(METRICS_REGISTRY)

const val FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS = "${METRICS_NS}_assign_linemanager_from_empty_form_by_lps"
val COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS: Counter =
    Counter.builder(FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_LPS)
        .description("Counts the number of assigned line managers from empty form by LPS")
        .register(METRICS_REGISTRY)

const val ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER =
    "${METRICS_NS}_assign_linemanager_from_empty_form_by_personnel_manager"
val COUNT_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER: Counter =
    Counter.builder(ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER)
        .description("Counts the number of assigned line managers from empty form by personnel manager")
        .register(METRICS_REGISTRY)

const val FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER =
    "${METRICS_NS}_assign_linemanager_from_empty_form_by_personnel_manager"
val COUNT_FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER: Counter =
    Counter.builder(FAILED_ASSIGN_LINEMANAGER_FROM_EMPTY_FORM_BY_PERSONNEL_MANAGER)
        .description("Counts the number of assigned line managers from empty form by personnel manager")
        .register(METRICS_REGISTRY)
