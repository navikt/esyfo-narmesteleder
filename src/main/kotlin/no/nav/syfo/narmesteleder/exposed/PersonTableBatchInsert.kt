package no.nav.syfo.narmesteleder.exposed

import no.nav.syfo.pdl.Person
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class PersonBatchInsertRow(
    val fnr: String,
    val status: String,
    val fornavn: String? = null,
    val mellomnavn: String? = null,
    val etternavn: String? = null,
    val foedselsdato: LocalDate? = null,
)

data class InsertedPerson(
    val id: UUID,
    val fnr: String,
    val status: String,
    val fornavn: String?,
    val mellomnavn: String?,
    val etternavn: String?,
    val foedselsdato: LocalDate?,
)

internal class PersonTableOps(private val transaction: Transaction) {
    fun findExistingFnrs(fnrs: Iterable<String>): Set<String> {
        val distinctFnrs = fnrs.distinct()
        if (distinctFnrs.isEmpty()) {
            return emptySet()
        }

        return PersonTable.selectAll()
            .where { PersonTable.fnr inList distinctFnrs }
            .map { it[PersonTable.fnr] }
            .toSet()
    }

    fun updatePersonFromPdl(
        fnr: String,
        person: Person,
    ): Int = PersonTable.update({ PersonTable.fnr eq fnr }) {
        it[fornavn] = person.name.fornavn
        it[mellomnavn] = person.name.mellomnavn
        it[etternavn] = person.name.etternavn
        it[foedselsdato] = person.foedselsdato?.foedselsdato
        it[updated] = OffsetDateTime.now(ZoneOffset.UTC)
    }

    fun batchInsertIgnoreExisting(rows: Iterable<PersonBatchInsertRow>): List<InsertedPerson> {
        val rowsToInsert = rows.toList()
        if (rowsToInsert.isEmpty()) {
            return emptyList()
        }
        with(PersonTable) {
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
                this[PersonTable.foedselsdato] = row.foedselsdato
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
                    foedselsdato = insertedRow[PersonTable.foedselsdato],
                )
            }
            return insertPersons
        }
    }
}

/**
 * Provides convenient access to [PersonTableOps] from within an Exposed `transaction` block.
 */
internal val Transaction.personTable: PersonTableOps
    get() = PersonTableOps(this)
