package no.nav.syfo.narmesteleder.api.v1

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY

const val LINEMANAGER_STATISTICS_TOTAL = "${METRICS_NS}_linemanager_statistics_total"
private const val PRINCIPAL_TYPE_TAG = "principal_type"

private val countLinemanagerStatisticsBySystem: Counter = Counter.builder(LINEMANAGER_STATISTICS_TOTAL)
    .description("Counts successful line manager statistics requests")
    .tag(PRINCIPAL_TYPE_TAG, "system")
    .register(METRICS_REGISTRY)

private val countLinemanagerStatisticsByUser: Counter = Counter.builder(LINEMANAGER_STATISTICS_TOTAL)
    .description("Counts successful line manager statistics requests")
    .tag(PRINCIPAL_TYPE_TAG, "user")
    .register(METRICS_REGISTRY)

fun countLinemanagerStatistics(principal: Principal) {
    when (principal) {
        is SystemPrincipal -> countLinemanagerStatisticsBySystem.increment()
        is UserPrincipal -> countLinemanagerStatisticsByUser.increment()
    }
}
