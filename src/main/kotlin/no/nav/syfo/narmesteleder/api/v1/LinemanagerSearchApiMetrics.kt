package no.nav.syfo.narmesteleder.api.v1

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val LINEMANAGER_SEARCH_TOTAL = "${METRICS_NS}_linemanager_search_total"
private const val PRINCIPAL_TYPE_TAG = "principal_type"
private const val SYSTEM_PRINCIPAL_TYPE = "system"
private const val USER_PRINCIPAL_TYPE = "user"

private val countLinemanagerSearchBySystem: Counter = Counter.builder(LINEMANAGER_SEARCH_TOTAL)
    .description("Counts successful line manager search requests")
    .tag(PRINCIPAL_TYPE_TAG, SYSTEM_PRINCIPAL_TYPE)
    .register(METRICS_REGISTRY)

private val countLinemanagerSearchByUser: Counter = Counter.builder(LINEMANAGER_SEARCH_TOTAL)
    .description("Counts successful line manager search requests")
    .tag(PRINCIPAL_TYPE_TAG, USER_PRINCIPAL_TYPE)
    .register(METRICS_REGISTRY)

fun countLinemanagerSearch(principal: Principal) {
    when (principal) {
        is SystemPrincipal -> countLinemanagerSearchBySystem.increment()
        is UserPrincipal -> countLinemanagerSearchByUser.increment()
    }
}
