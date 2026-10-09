package no.nav.syfo.narmestelederrelasjon.application

fun interface LeesahNarmestelederrelasjonStore {
    /** Upserts the relations and inserts missing persons as pending, in one transaction. */
    fun store(relasjoner: List<LeesahNarmestelederrelasjon>, personFnrs: List<String>)
}
