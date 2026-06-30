package no.nav.syfo.narmesteleder.domain

data class Linemanager(
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val lastName: String,
    val orgNumber: OrganizationNumber,
    val manager: Manager,
)
