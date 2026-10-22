package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederrelasjon.application.PersonIdentifiers
import no.nav.syfo.narmestelederrelasjon.application.PersonIdentifiersLookup
import no.nav.syfo.narmestelederrelasjon.application.PersonIdentifiersLookupOperation
import no.nav.syfo.narmestelederrelasjon.application.PersonIdentifiersLookupResult
import no.nav.syfo.platform.observability.METRICS_NS
import no.nav.syfo.platform.observability.METRICS_REGISTRY
import no.nav.syfo.platform.upstream.UpstreamResult

internal const val PERSON_IDENTIFIERS_LOOKUP_TOTAL = "${METRICS_NS}_narmestelederrelasjon_person_identifiers_lookup_total"

class PdlPersonIdentifiersLookup(
    private val client: PdlClient,
    private val registry: MeterRegistry = METRICS_REGISTRY,
) : PersonIdentifiersLookup {
    override suspend fun find(
        personIdent: PersonalIdentificationNumber,
        operation: PersonIdentifiersLookupOperation,
    ): PersonIdentifiersLookupResult {
        val result = when (val identifiers = client.getPersonIdentifiers(personIdent.value)) {
            is UpstreamResult.Failure -> PersonIdentifiersLookupResult.Unavailable(identifiers.failure)
            is UpstreamResult.Success ->
                identifiers.value
                    ?.let {
                        PersonIdentifiersLookupResult.Found(
                            PersonIdentifiers(
                                PersonalIdentificationNumber(it.current),
                                it.all.map(::PersonalIdentificationNumber),
                            ),
                        )
                    }
                    ?: PersonIdentifiersLookupResult.NotFound
        }
        countLookup(registry, operation, result)
        return result
    }
}

private fun countLookup(
    registry: MeterRegistry,
    operation: PersonIdentifiersLookupOperation,
    result: PersonIdentifiersLookupResult,
) {
    val outcome = when (result) {
        is PersonIdentifiersLookupResult.Found -> "found"
        PersonIdentifiersLookupResult.NotFound -> "not_found"
        is PersonIdentifiersLookupResult.Unavailable -> "unavailable"
    }
    Counter.builder(PERSON_IDENTIFIERS_LOOKUP_TOTAL)
        .description("Counts fresh PDL person identity lookups")
        .tag("operation", operation.metricValue)
        .tag("outcome", outcome)
        .register(registry)
        .increment()
}
