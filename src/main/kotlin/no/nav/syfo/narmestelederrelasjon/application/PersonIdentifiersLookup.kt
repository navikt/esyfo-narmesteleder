package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface PersonIdentifiersLookup {
    suspend fun find(
        personIdent: PersonalIdentificationNumber,
        operation: PersonIdentifiersLookupOperation,
    ): PersonIdentifiersLookupResult
}

enum class PersonIdentifiersLookupOperation(
    val metricValue: String,
) {
    ESTABLISH_RELATION("establish_relation"),
    GET_RELATION("get_relation"),
}

sealed interface PersonIdentifiersLookupResult {
    data class Found(val identifiers: PersonIdentifiers) : PersonIdentifiersLookupResult
    data object NotFound : PersonIdentifiersLookupResult
    data class Unavailable(val failure: UpstreamFailure) : PersonIdentifiersLookupResult
}

data class PersonIdentifiers(
    val current: PersonalIdentificationNumber,
    val all: List<PersonalIdentificationNumber>,
)
