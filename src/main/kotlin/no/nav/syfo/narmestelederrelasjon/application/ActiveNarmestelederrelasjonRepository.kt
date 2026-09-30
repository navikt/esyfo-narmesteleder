package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID

interface ActiveNarmestelederrelasjonRepository {
    suspend fun findActive(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber): List<ActiveNarmestelederrelasjon>
}

data class ActiveNarmestelederrelasjon(
    val id: UUID,
    val managerIdent: PersonIdent,
    val managerEmail: String,
    val activeFrom: Instant,
)
