package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import java.util.UUID

interface NarmestelederrelasjonRepository {
    suspend fun findById(id: UUID): NarmestelederrelasjonLookup?
    suspend fun findRevocableById(id: UUID): RevocableNarmestelederrelasjon?
}

data class NarmestelederrelasjonLookup(
    val id: UUID,
    val organizationNumber: OrganizationNumber,
    val employeeIdent: PersonalIdentificationNumber,
    val employeeFirstName: String?,
    val employeeMiddleName: String?,
    val employeeLastName: String?,
    val isActive: Boolean,
)

data class RevocableNarmestelederrelasjon(
    val id: UUID,
    val employeeIdent: PersonalIdentificationNumber,
    val managerIdent: PersonalIdentificationNumber,
    val organizationNumber: OrganizationNumber,
    val isActive: Boolean,
)
