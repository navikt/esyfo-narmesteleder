package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjonStore
import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedLeesahNarmestelederrelasjonStore(
    private val database: Database,
) : LeesahNarmestelederrelasjonStore {
    override fun store(relasjoner: List<LeesahNarmestelederrelasjon>, personFnrs: List<String>) {
        transaction(database) {
            relasjoner.forEach { narmestelederTable.upsertFromLeesah(it) }
            personTable.batchInsertIgnoreExisting(
                personFnrs.map { PersonBatchInsertRow(fnr = it, status = PersonStatus.PENDING.name) },
            )
        }
    }
}
