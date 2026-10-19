package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonalIdentificationNumber

fun interface LeesahNarmestelederrelasjonRepository {
    /** Upserts the relations and inserts missing persons as pending, in one transaction. */
    fun upsertAll(relasjoner: List<NarmestelederrelasjonUpsert>, persons: List<PersonalIdentificationNumber>)
}
