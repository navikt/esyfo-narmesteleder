package no.nav.syfo.narmesteleder.domain

import java.util.UUID

data class LinemanagerRequirementWrite(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val managerIdentificationNumber: String,
    val leesahStatus: String,
    val revokedLinemanagerId: UUID,
)
