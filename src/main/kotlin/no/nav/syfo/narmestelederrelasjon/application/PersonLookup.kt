package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface PersonLookup {
    suspend fun find(personIdent: PersonalIdentificationNumber): PersonLookupResult
}

sealed interface PersonLookupResult {
    data class Found(val person: PersonDetails) : PersonLookupResult
    data object NotFound : PersonLookupResult
    data class Unavailable(val failure: UpstreamFailure) : PersonLookupResult
}

data class PersonDetails(
    val personIdent: PersonalIdentificationNumber,
    val name: PersonNameDetails,
)
