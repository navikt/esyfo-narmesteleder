package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID

data class LinemanagerRequirementRead(
    val id: UUID,
    val employeeIdentificationNumber: PersonIdent,
    val orgNumber: OrganizationNumber,
    val orgName: String? = null,
    val mainOrgNumber: OrganizationNumber,
    val managerIdentificationNumber: PersonIdent? = null,
    val name: Name,
    val created: Instant,
    val updated: Instant,
    val status: LineManagerRequirementStatus,
    val revokedBy: RevokedBy?
)
