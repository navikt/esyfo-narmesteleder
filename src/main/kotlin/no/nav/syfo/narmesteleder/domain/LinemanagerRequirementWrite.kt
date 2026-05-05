package no.nav.syfo.narmesteleder.domain

import java.util.UUID

data class LinemanagerRequirementWrite(
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val orgNumber: OrganizationNumber,
    val managerIdentificationNumber: PersonalIdentificationNumber? = null,
    val behovReason: BehovReason,
    val revokedLinemanagerId: UUID? = null,
)
