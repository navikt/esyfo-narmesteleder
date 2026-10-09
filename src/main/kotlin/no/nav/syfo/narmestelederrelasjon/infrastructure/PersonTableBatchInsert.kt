package no.nav.syfo.narmestelederrelasjon.infrastructure

import org.jetbrains.exposed.v1.jdbc.batchInsert
import java.time.LocalDate
import java.util.UUID

internal data class PersonBatchInsertRow(
    val fnr: String,
    val status: String,
    val fornavn: String? = null,
    val mellomnavn: String? = null,
    val etternavn: String? = null,
    val foedselsdato: LocalDate? = null,
)

internal data class InsertedPerson(
    val id: UUID,
    val fnr: String,
    val status: String,
    val fornavn: String?,
    val mellomnavn: String?,
    val etternavn: String?,
    val foedselsdato: LocalDate?,
)

/** Inserts the rows, skipping any fnr that already exists. Returns only the rows that were inserted. */
internal fun PersonTable.batchInsertIgnoreExisting(rows: Iterable<PersonBatchInsertRow>): List<InsertedPerson> {
    val rowsToInsert = rows.toList()
    if (rowsToInsert.isEmpty()) {
        return emptyList()
    }
    return batchInsert(
        data = rowsToInsert,
        ignore = true,
        shouldReturnGeneratedValues = true,
    ) { row ->
        this[fnr] = row.fnr
        this[status] = row.status
        this[fornavn] = row.fornavn
        this[mellomnavn] = row.mellomnavn
        this[etternavn] = row.etternavn
        this[foedselsdato] = row.foedselsdato
    }.mapNotNull { insertedRow ->
        if (!insertedRow.hasValue(id)) {
            return@mapNotNull null
        }
        InsertedPerson(
            id = insertedRow[id].value,
            fnr = insertedRow[fnr],
            status = insertedRow[status],
            fornavn = insertedRow[fornavn],
            mellomnavn = insertedRow[mellomnavn],
            etternavn = insertedRow[etternavn],
            foedselsdato = insertedRow[foedselsdato],
        )
    }
}
