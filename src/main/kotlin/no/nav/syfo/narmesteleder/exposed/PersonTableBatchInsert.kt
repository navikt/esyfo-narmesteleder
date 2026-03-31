package no.nav.syfo.narmesteleder.exposed

import org.jetbrains.exposed.v1.jdbc.batchInsert

data class PersonBatchInsertRow(
    val fnr: String,
    val status: String,
    val fornavn: String? = null,
    val etternavn: String? = null,
)

fun PersonTable.batchInsertIgnoreExisting(rows: Iterable<PersonBatchInsertRow>) {
    val rowsToInsert = rows.toList()
    if (rowsToInsert.isEmpty()) {
        return
    }

    batchInsert(
        data = rowsToInsert,
        ignore = true,
        shouldReturnGeneratedValues = false,
    ) { row ->
        this[PersonTable.fnr] = row.fnr
        this[PersonTable.status] = row.status
        this[PersonTable.fornavn] = row.fornavn
        this[PersonTable.etternavn] = row.etternavn
    }
}
