package no.nav.syfo.narmesteleder.domain

import java.util.UUID

data class LinemanagerRequirementRead(
    val id: UUID,
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val mainOrgnumber: String,
    val managerIdentificationNumber: String,
    val name: Name
)
