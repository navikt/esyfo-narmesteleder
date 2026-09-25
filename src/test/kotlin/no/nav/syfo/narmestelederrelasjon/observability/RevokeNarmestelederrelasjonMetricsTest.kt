package no.nav.syfo.narmestelederrelasjon.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmestelederrelasjon.application.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonResult

class RevokeNarmestelederrelasjonMetricsTest :
    FunSpec({
        test("increments only the legacy outcome tag for successes") {
            val outcomes = listOf(
                RevocationInitiator.EMPLOYEE to "revoked_by_employee",
                RevocationInitiator.LINEMANAGER to "revoked_by_linemanager",
                RevocationInitiator.PERSONNEL_MANAGER to "revoked_by_personnel_manager",
                RevocationInitiator.LPS to "revoked_by_lps",
            )
            outcomes.forEach { (initiator, tag) ->
                val counter = METRICS_REGISTRY.find(LINEMANAGER_REVOKE_BY_ID_TOTAL).tag("outcome", tag).counter()
                val before = counter?.count() ?: 0.0
                countLinemanagerRevokeById(RevokeNarmestelederrelasjonResult.Revoked(initiator))
                METRICS_REGISTRY.find(LINEMANAGER_REVOKE_BY_ID_TOTAL).tag("outcome", tag).counter()?.count() shouldBe before + 1
            }
            val already = METRICS_REGISTRY.find(LINEMANAGER_REVOKE_BY_ID_TOTAL).tag("outcome", "already_revoked").counter()
            val before = already?.count() ?: 0.0
            countLinemanagerRevokeById(RevokeNarmestelederrelasjonResult.AlreadyRevoked)
            already?.count() shouldBe before + 1
        }

        test("does not count a missing relation or denied access") {
            val counters = METRICS_REGISTRY.find(LINEMANAGER_REVOKE_BY_ID_TOTAL).counters()
            val before = counters.sumOf { it.count() }
            countLinemanagerRevokeById(
                RevokeNarmestelederrelasjonResult.NotFound(RevokeNarmestelederrelasjonResult.Reason.RELATION_NOT_FOUND),
            )
            countLinemanagerRevokeById(
                RevokeNarmestelederrelasjonResult.NotFound(RevokeNarmestelederrelasjonResult.Reason.ACCESS_DENIED),
            )
            counters.sumOf { it.count() } shouldBe before
        }
    })
