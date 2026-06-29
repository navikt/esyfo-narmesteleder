package no.nav.syfo.narmesteleder.domain

data class Linemanager(
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val employeeLastName: String,
    val orgNumber: OrganizationNumber,
    val manager: Manager,
)
