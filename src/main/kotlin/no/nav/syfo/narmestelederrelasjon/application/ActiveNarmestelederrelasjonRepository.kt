package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import java.time.Instant
import java.util.UUID

interface ActiveNarmestelederrelasjonRepository {
    suspend fun findActive(employeeIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber): List<ActiveNarmestelederrelasjon>
}

data class ActiveNarmestelederrelasjon(
    val id: UUID,
    val managerIdent: PersonalIdentificationNumber,
    val managerEmail: String,
    val activeFrom: Instant,
)
