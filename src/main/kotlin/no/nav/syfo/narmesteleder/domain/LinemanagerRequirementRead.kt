package no.nav.syfo.narmesteleder.domain

import java.util.UUID

data class LinemanagerRequirementRead(
    val id: UUID,
    val employeeIdentificationNumber: String,
    val orgNumber: String,
    val orgName: String? = null,
    val mainOrgNumber: String,
    val managerIdentificationNumber: String,
    val name: Name
)
