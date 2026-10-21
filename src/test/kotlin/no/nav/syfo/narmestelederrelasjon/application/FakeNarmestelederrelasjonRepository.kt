package no.nav.syfo.narmestelederrelasjon.application

import java.util.UUID

internal class FakeNarmestelederrelasjonRepository(
    var lookupById: (UUID) -> NarmestelederrelasjonLookup? = { error("Unexpected findById($it)") },
    var revocableById: (UUID) -> RevocableNarmestelederrelasjon? = { error("Unexpected findRevocableById($it)") },
    private val effects: MutableList<String> = mutableListOf(),
) : NarmestelederrelasjonRepository {
    val findByIdCalls = mutableListOf<UUID>()
    val findRevocableByIdCalls = mutableListOf<UUID>()

    override suspend fun findById(id: UUID): NarmestelederrelasjonLookup? {
        findByIdCalls += id
        effects += "lookup"
        return lookupById(id)
    }

    override suspend fun findRevocableById(id: UUID): RevocableNarmestelederrelasjon? {
        findRevocableByIdCalls += id
        effects += "lookup"
        return revocableById(id)
    }

    fun reset() {
        lookupById = { error("Unexpected findById($it)") }
        revocableById = { error("Unexpected findRevocableById($it)") }
        findByIdCalls.clear()
        findRevocableByIdCalls.clear()
    }
}

/** Returns [results] for successive calls and keeps returning the last one. */
internal fun <T> inOrder(vararg results: T): (UUID) -> T {
    val remaining = ArrayDeque(results.toList())
    return { if (remaining.size > 1) remaining.removeFirst() else remaining.first() }
}
