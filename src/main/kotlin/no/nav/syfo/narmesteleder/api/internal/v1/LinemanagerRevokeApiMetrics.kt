package no.nav.syfo.narmesteleder.api.internal.v1

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmesteleder.service.RevokeOutcome

const val LINEMANAGER_REVOKE_BY_ID_TOTAL = "${METRICS_NS}_linemanager_revoke_by_id_total"
private const val OUTCOME_TAG = "outcome"

private fun revokeCounter(outcome: String): Counter = Counter.builder(LINEMANAGER_REVOKE_BY_ID_TOTAL)
    .description("Counts revocations of a line manager relation requested by the employee or the line manager")
    .tag(OUTCOME_TAG, outcome)
    .register(METRICS_REGISTRY)

private val countRevokedByEmployee: Counter = revokeCounter("revoked_by_employee")
private val countRevokedByLinemanager: Counter = revokeCounter("revoked_by_linemanager")
private val countAlreadyRevoked: Counter = revokeCounter("already_revoked")

fun countLinemanagerRevokeById(outcome: RevokeOutcome) {
    when (outcome) {
        is RevokeOutcome.AlreadyRevoked -> countAlreadyRevoked.increment()
        is RevokeOutcome.Revoked -> when (outcome.revokedBy) {
            RevokedBy.EMPLOYEE -> countRevokedByEmployee.increment()
            RevokedBy.LINEMANAGER -> countRevokedByLinemanager.increment()
            RevokedBy.LPS -> Unit
        }
    }
}
