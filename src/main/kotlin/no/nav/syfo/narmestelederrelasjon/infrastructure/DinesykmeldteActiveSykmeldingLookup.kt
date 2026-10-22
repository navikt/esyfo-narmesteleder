package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingOperation
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingResult
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamResult

internal const val ACTIVE_SYKMELDING_LOOKUP_TOTAL = "${METRICS_NS}_narmestelederrelasjon_active_sykmelding_lookup_total"

class DinesykmeldteActiveSykmeldingLookup(
    private val client: DinesykmeldteClient,
    private val registry: MeterRegistry = METRICS_REGISTRY,
) : ActiveSykmeldingLookup {
    override suspend fun findActiveSykmelding(
        personIdents: List<PersonalIdentificationNumber>,
        organizationNumber: OrganizationNumber,
        operation: ActiveSykmeldingOperation,
    ): ActiveSykmeldingResult {
        var firstFailure: UpstreamFailure? = null
        for ((index, personIdent) in personIdents.withIndex()) {
            when (val result = client.getIsActiveSykmelding(fnr = personIdent.value, orgnummer = organizationNumber.value)) {
                is UpstreamResult.Success -> if (result.value) {
                    countLookup(registry, operation, outcome = "found", historicalHit = index > 0)
                    return ActiveSykmeldingResult.Found
                }

                is UpstreamResult.Failure -> if (firstFailure == null) {
                    firstFailure = result.failure
                }
            }
        }
        return firstFailure?.let {
            countLookup(registry, operation, outcome = "unavailable", historicalHit = false)
            ActiveSykmeldingResult.Unavailable(it)
        } ?: ActiveSykmeldingResult.NotFound.also {
            countLookup(registry, operation, outcome = "not_found", historicalHit = false)
        }
    }
}

private fun countLookup(
    registry: MeterRegistry,
    operation: ActiveSykmeldingOperation,
    outcome: String,
    historicalHit: Boolean,
) {
    Counter.builder(ACTIVE_SYKMELDING_LOOKUP_TOTAL)
        .description("Counts active sick leave lookups across current and historical identities")
        .tag("operation", operation.metricValue)
        .tag("outcome", outcome)
        .tag("historical_hit", historicalHit.toString())
        .register(registry)
        .increment()
}
