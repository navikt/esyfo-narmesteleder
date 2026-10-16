package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonIdent

internal class InMemoryRelationPersonRepository : RelationPersonRepository {
    enum class Status { PENDING, ENRICHED, NOT_FOUND }

    data class Row(val status: Status, val details: RegisteredPerson? = null)

    val rows = linkedMapOf<PersonIdent, Row>()
    val findPendingLimits = mutableListOf<Int>()
    var updateRegisteredDetailsCalls = 0

    /** Persons removed from the projection between lookup and update. */
    val disappearBeforeUpdate = mutableSetOf<PersonIdent>()

    fun add(fnr: String, status: Status = Status.PENDING) {
        rows[PersonIdent(fnr)] = Row(status)
    }

    fun row(fnr: String): Row = rows.getValue(PersonIdent(fnr))

    fun count(status: Status): Int = rows.values.count { it.status == status }

    override suspend fun findPending(limit: Int): List<PersonIdent> {
        findPendingLimits += limit
        return rows.filterValues { it.status == Status.PENDING }.keys.take(limit)
    }

    override suspend fun saveEnrichment(enriched: Map<PersonIdent, RegisteredPerson>, notFound: Collection<PersonIdent>) {
        enriched.forEach { (ident, person) -> rows[ident] = Row(Status.ENRICHED, person) }
        notFound.forEach { ident -> rows[ident] = rows.getValue(ident).copy(status = Status.NOT_FOUND) }
    }

    override suspend fun findExisting(personIdents: Collection<PersonIdent>): List<PersonIdent> = personIdents.filter(rows::containsKey)

    override suspend fun updateRegisteredDetails(persons: Map<PersonIdent, RegisteredPerson>): Set<PersonIdent> {
        updateRegisteredDetailsCalls++
        disappearBeforeUpdate.forEach(rows::remove)
        return persons.filterKeys(rows::containsKey)
            .onEach { (ident, person) -> rows[ident] = rows.getValue(ident).copy(details = person) }
            .keys
    }
}

internal class FakeBulkPersonLookup(
    private val answer: (List<PersonIdent>) -> Map<PersonIdent, BulkPersonLookupResult> = { emptyMap() },
) : BulkPersonLookup {
    val requests = mutableListOf<List<PersonIdent>>()

    override suspend fun findAll(personIdents: List<PersonIdent>): Map<PersonIdent, BulkPersonLookupResult> {
        requests += personIdents
        return answer(personIdents)
    }
}

internal class RecordingRelationPersonNameUpdateMetrics : RelationPersonNameUpdateMetrics {
    val lookupFailed = mutableListOf<Int>()

    override fun recordLookupFailed(count: Int) {
        lookupFailed += count
    }
}

internal fun registeredPerson(firstName: String = "Ola", lastName: String = "Nordmann") = RegisteredPerson(
    firstName = firstName,
    middleName = null,
    lastName = lastName,
    birthDate = null,
)

internal fun found(person: RegisteredPerson = registeredPerson()) = BulkPersonLookupResult.Found(person)
