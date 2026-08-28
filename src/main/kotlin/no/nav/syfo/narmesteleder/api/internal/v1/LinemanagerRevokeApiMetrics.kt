package no.nav.syfo.narmesteleder.api.internal.v1

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.RevokeInitiator
import no.nav.syfo.narmesteleder.service.RevokeOutcome

const val LINEMANAGER_REVOKE_BY_ID_TOTAL = "${METRICS_NS}_linemanager_revoke_by_id_total"
private const val OUTCOME_TAG = "outcome"

private fun revokeCounter(outcome: String): Counter = Counter.builder(LINEMANAGER_REVOKE_BY_ID_TOTAL)
    .description("Counts revocations of a line manager relation requested through the revoke by id endpoint")
    .tag(OUTCOME_TAG, outcome)
    .register(METRICS_REGISTRY)

private val countRevokedByEmployee: Counter = revokeCounter("revoked_by_employee")
private val countRevokedByLinemanager: Counter = revokeCounter("revoked_by_linemanager")
private val countRevokedByPersonnelManager: Counter = revokeCounter("revoked_by_personnel_manager")
private val countRevokedByLps: Counter = revokeCounter("revoked_by_lps")
private val countAlreadyRevoked: Counter = revokeCounter("already_revoked")

fun countLinemanagerRevokeById(outcome: RevokeOutcome) {
    when (outcome) {
        is RevokeOutcome.AlreadyRevoked -> countAlreadyRevoked.increment()
        is RevokeOutcome.Revoked -> when (outcome.initiator) {
            RevokeInitiator.EMPLOYEE -> countRevokedByEmployee.increment()
            RevokeInitiator.LINEMANAGER -> countRevokedByLinemanager.increment()
            RevokeInitiator.PERSONNEL_MANAGER -> countRevokedByPersonnelManager.increment()
            RevokeInitiator.LPS -> countRevokedByLps.increment()
        }
    }
}
