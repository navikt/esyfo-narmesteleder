package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonIdent

/** Persistence for the relation-owned `RelationPerson` projection. */
interface RelationPersonRepository {
    /** Returns up to [limit] persons waiting for enrichment, oldest first. */
    suspend fun findPending(limit: Int): List<PersonIdent>

    /** Stores the enrichment outcome for a batch of pending persons in one transaction. */
    suspend fun saveEnrichment(enriched: Map<PersonIdent, RegisteredPerson>, notFound: Collection<PersonIdent>)
}
