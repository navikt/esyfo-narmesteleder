package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.util.UUID

interface NarmestelederrelasjonRepository {
    suspend fun findById(id: UUID): NarmestelederrelasjonLookup?
    suspend fun findRevocableById(id: UUID): RevocableNarmestelederrelasjon?
}

data class NarmestelederrelasjonLookup(
    val id: UUID,
    val organizationNumber: OrganizationNumber,
    val employeeIdent: PersonIdent,
    val employeeFirstName: String?,
    val employeeMiddleName: String?,
    val employeeLastName: String?,
    val isActive: Boolean,
)

data class RevocableNarmestelederrelasjon(
    val id: UUID,
    val employeeIdent: PersonIdent,
    val managerIdent: PersonIdent,
    val organizationNumber: OrganizationNumber,
    val isActive: Boolean,
)
