package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.LeesahNarmestelederrelasjonRepository
import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExposedLeesahNarmestelederrelasjonRepository(
    private val database: Database,
) : LeesahNarmestelederrelasjonRepository {
    override fun upsertAll(relasjoner: List<LeesahNarmestelederrelasjon>, personFnrs: List<String>) {
        transaction(database) {
            relasjoner.forEach { narmestelederTable.upsertFromLeesah(it) }
            personTable.batchInsertIgnoreExisting(
                personFnrs.map { PersonBatchInsertRow(fnr = it, status = PersonStatus.PENDING.name) },
            )
        }
    }
}
