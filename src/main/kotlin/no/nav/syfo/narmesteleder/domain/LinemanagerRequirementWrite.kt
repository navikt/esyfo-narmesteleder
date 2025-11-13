package no.nav.syfo.narmesteleder.domain

import java.util.UUID

data class LinemanagerRequirementWrite(
    val employeeIdentificationNumber: String,
    val orgNumber: String,
    val managerIdentificationNumber: String? = null,
    val leesahStatus: String? = null,
    val revokedLinemanagerId: UUID? = null,
)
