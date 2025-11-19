package no.nav.syfo.narmesteleder.domain

import java.time.Instant
import java.util.UUID

data class LinemanagerRequirementRead(
    val id: UUID,
    val employeeIdentificationNumber: String,
    val orgNumber: String,
    val orgName: String? = null,
    val mainOrgNumber: String,
    val managerIdentificationNumber: String? = null,
    val name: Name,
    val created: Instant,
    val updated: Instant,
    val status: BehovStatus,
    val revokedBy: RevokedBy?
)
