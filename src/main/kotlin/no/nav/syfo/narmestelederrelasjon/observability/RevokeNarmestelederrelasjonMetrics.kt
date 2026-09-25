package no.nav.syfo.narmestelederrelasjon.observability

import io.micrometer.core.instrument.Counter
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonResult

const val LINEMANAGER_REVOKE_BY_ID_TOTAL = "${METRICS_NS}_linemanager_revoke_by_id_total"

private fun revokeCounter(outcome: String): Counter = Counter.builder(LINEMANAGER_REVOKE_BY_ID_TOTAL)
    .description("Counts revocations of a line manager relation requested through the revoke by id endpoint")
    .tag("outcome", outcome)
    .register(METRICS_REGISTRY)

private val countRevokedByEmployee = revokeCounter("revoked_by_employee")
private val countRevokedByLinemanager = revokeCounter("revoked_by_linemanager")
private val countRevokedByPersonnelManager = revokeCounter("revoked_by_personnel_manager")
private val countRevokedByLps = revokeCounter("revoked_by_lps")
private val countAlreadyRevoked = revokeCounter("already_revoked")

fun countLinemanagerRevokeById(result: RevokeNarmestelederrelasjonResult) {
    when (result) {
        RevokeNarmestelederrelasjonResult.AlreadyRevoked -> countAlreadyRevoked.increment()
        is RevokeNarmestelederrelasjonResult.NotFound -> Unit
        is RevokeNarmestelederrelasjonResult.Revoked -> when (result.initiator) {
            RevocationInitiator.EMPLOYEE -> countRevokedByEmployee.increment()
            RevocationInitiator.LINEMANAGER -> countRevokedByLinemanager.increment()
            RevocationInitiator.PERSONNEL_MANAGER -> countRevokedByPersonnelManager.increment()
            RevocationInitiator.LPS -> countRevokedByLps.increment()
        }
    }
}
