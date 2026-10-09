package no.nav.syfo.narmestelederrelasjon.application

fun interface LeesahNarmestelederrelasjonRepository {
    /** Upserts the relations and inserts missing persons as pending, in one transaction. */
    fun upsertAll(relasjoner: List<LeesahNarmestelederrelasjon>, personFnrs: List<String>)
}
