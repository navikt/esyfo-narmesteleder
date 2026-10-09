package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonUpsert
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedLeesahNarmestelederrelasjonRepository(
    private val database: Database,
) : LeesahNarmestelederrelasjonRepository {
    override fun upsertAll(relasjoner: List<NarmestelederrelasjonUpsert>, persons: List<PersonIdent>) {
        transaction(database) {
            relasjoner.forEach { NarmestelederTable.upsertRelasjon(it) }
            PersonTable.insertPendingIgnoringExisting(persons)
        }
    }
}
