package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID

interface EmployeeNarmestelederrelasjonRepository {
    suspend fun findActive(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber?): List<EmployeeNarmestelederrelasjon>
}

data class EmployeeNarmestelederrelasjon(
    val id: UUID,
    val organizationNumber: OrganizationNumber,
    val activeFrom: Instant,
    val managerFirstName: String?,
    val managerMiddleName: String?,
    val managerLastName: String?,
    val managerEmail: String,
    val managerMobile: String,
)
