package no.nav.syfo.narmestelederbehov.api

import io.micrometer.core.instrument.Counter
import no.nav.syfo.platform.auth.Principal
import no.nav.syfo.platform.auth.SystemPrincipal
import no.nav.syfo.platform.auth.UserPrincipal
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY

const val FULFILL_LINEMANAGER_REQUIREMENT_BY_LPS = "${METRICS_NS}_fulfill_linemanager_requirement_by_lps"
val COUNT_FULFILL_LINEMANAGER_REQUIREMENT_BY_LPS: Counter = Counter.builder(FULFILL_LINEMANAGER_REQUIREMENT_BY_LPS)
    .description("Counts the number of fulfilled requirements performed by LPS")
    .register(METRICS_REGISTRY)

const val FULFILL_LINEMANAGER_BY_PERSONNEL_MANAGER =
    "${METRICS_NS}_fulfill_linemanager_requirement_by_personnel_manager"
val COUNT_FULFILL_LINEMANAGER_BY_PERSONNEL_MANAGER: Counter = Counter.builder(FULFILL_LINEMANAGER_BY_PERSONNEL_MANAGER)
    .description("Counts the number of fulfilled requirements performed by personnel manager")
    .register(METRICS_REGISTRY)

fun Principal.countFulfilledRequirement() = when (this) {
    is SystemPrincipal -> COUNT_FULFILL_LINEMANAGER_REQUIREMENT_BY_LPS.increment()
    is UserPrincipal -> COUNT_FULFILL_LINEMANAGER_BY_PERSONNEL_MANAGER.increment()
}
