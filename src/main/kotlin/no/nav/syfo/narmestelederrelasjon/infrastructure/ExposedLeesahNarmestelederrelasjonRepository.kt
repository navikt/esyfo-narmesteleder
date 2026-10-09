package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.ValidLeesahNarmestelederrelasjon
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedLeesahNarmestelederrelasjonRepository(
    private val database: Database,
) : LeesahNarmestelederrelasjonRepository {
    override fun upsertAll(relasjoner: List<ValidLeesahNarmestelederrelasjon>, persons: List<PersonIdent>) {
        transaction(database) {
            relasjoner.forEach { NarmestelederTable.upsertFromLeesah(it) }
            PersonTable.insertPendingIgnoringExisting(persons)
        }
    }
}
