package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjonRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedLeesahNarmestelederrelasjonRepository(
    private val database: Database,
) : LeesahNarmestelederrelasjonRepository {
    override fun upsertAll(relasjoner: List<LeesahNarmestelederrelasjon>, personFnrs: List<String>) {
        transaction(database) {
            relasjoner.forEach { NarmestelederTable.upsertFromLeesah(it) }
            PersonTable.insertPendingIgnoringExisting(personFnrs)
        }
    }
}
