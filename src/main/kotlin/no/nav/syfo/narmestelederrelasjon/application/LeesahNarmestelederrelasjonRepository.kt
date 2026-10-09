package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonIdent

fun interface LeesahNarmestelederrelasjonRepository {
    /** Upserts the relations and inserts missing persons as pending, in one transaction. */
    fun upsertAll(relasjoner: List<ValidLeesahNarmestelederrelasjon>, persons: List<PersonIdent>)
}
