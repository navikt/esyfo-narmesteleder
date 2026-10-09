package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.jdbc.batchInsert

/** Inserts a [PersonStatus.PENDING] person for each fnr, leaving fnrs that already exist untouched. */
internal fun PersonTable.insertPendingIgnoringExisting(fnrs: Collection<String>) {
    if (fnrs.isEmpty()) {
        return
    }
    batchInsert(data = fnrs, ignore = true, shouldReturnGeneratedValues = false) { fnr ->
        this[PersonTable.fnr] = fnr
        this[status] = PersonStatus.PENDING.name
    }
}
