package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonalIdentificationNumber

/** Persistence for the relation-owned `RelationPerson` projection. */
interface RelationPersonRepository {
    /** Returns up to [limit] persons waiting for enrichment, oldest first. */
    suspend fun findPending(limit: Int): List<PersonalIdentificationNumber>

    /** Stores the enrichment outcome for a batch of pending persons in one transaction. */
    suspend fun saveEnrichment(enriched: Map<PersonalIdentificationNumber, RegisteredPerson>, notFound: Collection<PersonalIdentificationNumber>)

    /** Returns the given persons that exist in the projection. */
    suspend fun findExisting(personIdents: Collection<PersonalIdentificationNumber>): List<PersonalIdentificationNumber>

    /** Replaces the registered details of existing persons and returns the persons that were updated. */
    suspend fun updateRegisteredDetails(persons: Map<PersonalIdentificationNumber, RegisteredPerson>): Set<PersonalIdentificationNumber>
}
