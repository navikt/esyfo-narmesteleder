package no.nav.syfo.narmesteleder.domain

import java.time.Instant
import java.util.UUID

data class LinemanagerRead(
    val id: UUID,
    val orgNumber: OrganizationNumber,
    val activeFrom: Instant,
    val employee: LinemanagerPersonRead,
    val manager: LinemanagerManagerRead,
)

data class LinemanagerPersonRead(
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val name: Name?,
)

data class LinemanagerManagerRead(
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val name: Name?,
    val email: String,
    val mobile: String,
)
