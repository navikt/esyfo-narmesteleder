package no.nav.syfo.narmestelederrelasjon.application

import java.util.UUID

interface NarmestelederrelasjonRepository {
    suspend fun findById(id: UUID): NarmestelederrelasjonLookup?
    suspend fun findRevocableById(id: UUID): RevocableNarmestelederrelasjon?
}
