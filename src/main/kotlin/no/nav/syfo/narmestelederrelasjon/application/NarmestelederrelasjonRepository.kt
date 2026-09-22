package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import java.util.UUID

interface NarmestelederrelasjonRepository {
    suspend fun findActiveById(id: UUID): Narmestelederrelasjon?
}
