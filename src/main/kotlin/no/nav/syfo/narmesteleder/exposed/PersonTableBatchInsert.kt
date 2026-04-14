package no.nav.syfo.narmesteleder.exposed

import org.jetbrains.exposed.v1.jdbc.batchInsert
import java.util.UUID

data class PersonBatchInsertRow(
    val fnr: String,
    val status: String,
    val fornavn: String? = null,
    val mellomnavn: String? = null,
    val etternavn: String? = null,
)

data class InsertedPerson(
    val id: UUID,
    val fnr: String,
    val status: String,
    val fornavn: String?,
    val mellomnavn: String?,
    val etternavn: String?,
)

fun PersonTable.batchInsertIgnoreExisting(rows: Iterable<PersonBatchInsertRow>): List<InsertedPerson> {
    val rowsToInsert = rows.toList()
    if (rowsToInsert.isEmpty()) {
        return emptyList()
    }

    val insertPersons = batchInsert(
        data = rowsToInsert,
        ignore = true,
        shouldReturnGeneratedValues = true,
    ) { row ->
        this[PersonTable.fnr] = row.fnr
        this[PersonTable.status] = row.status
        this[PersonTable.fornavn] = row.fornavn
        this[PersonTable.mellomnavn] = row.mellomnavn
        this[PersonTable.etternavn] = row.etternavn
    }.mapNotNull { insertedRow ->
        if (!insertedRow.hasValue(PersonTable.id)) {
            return@mapNotNull null
        }

        InsertedPerson(
            id = insertedRow[PersonTable.id].value,
            fnr = insertedRow[PersonTable.fnr],
            status = insertedRow[PersonTable.status],
            fornavn = insertedRow[PersonTable.fornavn],
            mellomnavn = insertedRow[PersonTable.mellomnavn],
            etternavn = insertedRow[PersonTable.etternavn],
        )
    }
    return insertPersons
}
