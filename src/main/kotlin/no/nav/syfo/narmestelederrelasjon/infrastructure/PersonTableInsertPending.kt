package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.jdbc.batchInsert

/** Inserts a [PersonStatus.PENDING] person for each ident, leaving persons that already exist untouched. */
internal fun PersonTable.insertPendingIgnoringExisting(persons: Collection<PersonIdent>) {
    if (persons.isEmpty()) {
        return
    }
    batchInsert(data = persons, ignore = true, shouldReturnGeneratedValues = false) { person ->
        this[fnr] = person.value
        this[status] = PersonStatus.PENDING.name
    }
}
