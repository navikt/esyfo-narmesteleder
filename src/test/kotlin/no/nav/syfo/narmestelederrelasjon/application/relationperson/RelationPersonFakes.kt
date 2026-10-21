package no.nav.syfo.narmestelederrelasjon.application.relationperson

import no.nav.syfo.ident.PersonalIdentificationNumber

internal class InMemoryRelationPersonRepository : RelationPersonRepository {
    enum class Status { PENDING, ENRICHED, NOT_FOUND }

    data class Row(val status: Status, val details: RegisteredPerson? = null)

    val rows = linkedMapOf<PersonalIdentificationNumber, Row>()
    val findPendingLimits = mutableListOf<Int>()
    var updateRegisteredDetailsCalls = 0

    /** Persons removed from the projection between lookup and update. */
    val disappearBeforeUpdate = mutableSetOf<PersonalIdentificationNumber>()

    fun add(fnr: String, status: Status = Status.PENDING) {
        rows[PersonalIdentificationNumber(fnr)] = Row(status)
    }

    fun row(fnr: String): Row = rows.getValue(PersonalIdentificationNumber(fnr))

    fun count(status: Status): Int = rows.values.count { it.status == status }

    override suspend fun findPending(limit: Int): List<PersonalIdentificationNumber> {
        findPendingLimits += limit
        return rows.filterValues { it.status == Status.PENDING }.keys.take(limit)
    }

    override suspend fun saveEnrichment(enriched: Map<PersonalIdentificationNumber, RegisteredPerson>, notFound: Collection<PersonalIdentificationNumber>) {
        enriched.forEach { (ident, person) -> rows[ident] = Row(Status.ENRICHED, person) }
        notFound.forEach { ident -> rows[ident] = rows.getValue(ident).copy(status = Status.NOT_FOUND) }
    }

    override suspend fun findExisting(personIdents: Collection<PersonalIdentificationNumber>): List<PersonalIdentificationNumber> = personIdents.filter(rows::containsKey)

    override suspend fun updateRegisteredDetails(persons: Map<PersonalIdentificationNumber, RegisteredPerson>): Set<PersonalIdentificationNumber> {
        updateRegisteredDetailsCalls++
        disappearBeforeUpdate.forEach(rows::remove)
        return persons.filterKeys(rows::containsKey)
            .onEach { (ident, person) -> rows[ident] = rows.getValue(ident).copy(details = person) }
            .keys
    }
}

internal class FakeBulkPersonLookup(
    private val answer: (List<PersonalIdentificationNumber>) -> Map<PersonalIdentificationNumber, BulkPersonLookupResult> = { emptyMap() },
) : BulkPersonLookup {
    val requests = mutableListOf<List<PersonalIdentificationNumber>>()

    override suspend fun findAll(personIdents: List<PersonalIdentificationNumber>): Map<PersonalIdentificationNumber, BulkPersonLookupResult> {
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
