package no.nav.syfo.narmestelederrelasjon.application.relationperson

import no.nav.syfo.ident.PersonalIdentificationNumber

fun interface BulkPersonLookup {
    /**
     * Looks up the persons in the person register. A person that could not be looked up is missing from
     * the result, which is different from [BulkPersonLookupResult.NotFound].
     */
    suspend fun findAll(personIdents: List<PersonalIdentificationNumber>): Map<PersonalIdentificationNumber, BulkPersonLookupResult>
}

sealed interface BulkPersonLookupResult {
    data class Found(val person: RegisteredPerson) : BulkPersonLookupResult

    data object NotFound : BulkPersonLookupResult
}
