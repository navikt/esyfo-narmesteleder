package no.nav.syfo.narmestelederrelasjon.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.RegisteredPerson
import no.nav.syfo.narmestelederrelasjon.application.RelationPersonRepository
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ExposedRelationPersonRepository(
    private val database: Database,
) : RelationPersonRepository {
    override suspend fun findPending(limit: Int): List<PersonIdent> = inTransaction {
        PersonTable
            .select(PersonTable.fnr)
            .where { PersonTable.status eq PersonStatus.PENDING.name }
            .orderBy(PersonTable.created to SortOrder.ASC)
            .limit(limit)
            .map { PersonIdent(it[PersonTable.fnr]) }
    }

    override suspend fun saveEnrichment(enriched: Map<PersonIdent, RegisteredPerson>, notFound: Collection<PersonIdent>) {
        if (enriched.isEmpty() && notFound.isEmpty()) {
            return
        }
        inTransaction {
            enriched.forEach { (personIdent, person) ->
                PersonTable.update({ PersonTable.fnr eq personIdent.value }) {
                    it.setRegisteredDetails(person)
                    it[status] = PersonStatus.ENRICHED.name
                }
            }
            notFound.forEach { personIdent ->
                PersonTable.update({ PersonTable.fnr eq personIdent.value }) {
                    it[status] = PersonStatus.NOT_FOUND.name
                }
            }
        }
    }

    override suspend fun findExisting(personIdents: Collection<PersonIdent>): List<PersonIdent> = inTransaction {
        PersonTable
            .select(PersonTable.fnr)
            .where { PersonTable.fnr inList personIdents.map(PersonIdent::value) }
            .map { PersonIdent(it[PersonTable.fnr]) }
    }

    override suspend fun updateRegisteredDetails(persons: Map<PersonIdent, RegisteredPerson>): Set<PersonIdent> = inTransaction {
        val updatedAt = OffsetDateTime.now(ZoneOffset.UTC)
        persons
            .filter { (personIdent, person) ->
                PersonTable.update({ PersonTable.fnr eq personIdent.value }) {
                    it.setRegisteredDetails(person)
                    it[updated] = updatedAt
                } > 0
            }
            .keys
    }

    private suspend fun <T> inTransaction(block: () -> T): T = withContext(Dispatchers.IO) {
        suspendTransaction(db = database) { block() }
    }
}

private fun UpdateStatement.setRegisteredDetails(person: RegisteredPerson) {
    this[PersonTable.fornavn] = person.firstName
    this[PersonTable.mellomnavn] = person.middleName
    this[PersonTable.etternavn] = person.lastName
    this[PersonTable.foedselsdato] = person.birthDate
}
