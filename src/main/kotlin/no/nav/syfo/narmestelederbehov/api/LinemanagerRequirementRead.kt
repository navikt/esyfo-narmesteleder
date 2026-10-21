package no.nav.syfo.narmestelederbehov.api

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import java.time.Instant
import java.util.UUID

data class LinemanagerRequirementRead(
    val id: UUID,
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val orgNumber: OrganizationNumber,
    val orgName: String? = null,
    val mainOrgNumber: OrganizationNumber,
    val managerIdentificationNumber: PersonalIdentificationNumber? = null,
    val name: Name,
    val created: Instant,
    val updated: Instant,
    val status: LineManagerRequirementStatus,
    val revokedBy: RevokedBy?
)
