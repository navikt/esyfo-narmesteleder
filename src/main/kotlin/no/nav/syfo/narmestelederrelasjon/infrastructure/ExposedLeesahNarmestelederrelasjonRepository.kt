package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.leesah.LeesahNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.leesah.NarmestelederrelasjonUpsert
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedLeesahNarmestelederrelasjonRepository(
    private val database: Database,
) : LeesahNarmestelederrelasjonRepository {
    override fun upsertAll(relasjoner: List<NarmestelederrelasjonUpsert>, persons: List<PersonalIdentificationNumber>) {
        transaction(database) {
            relasjoner.forEach { NarmestelederTable.upsertRelasjon(it) }
            PersonTable.insertPendingIgnoringExisting(persons)
        }
    }
}
