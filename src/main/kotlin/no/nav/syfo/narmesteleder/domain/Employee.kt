package no.nav.syfo.narmesteleder.domain

data class Employee(
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val orgNumber: OrganizationNumber,
    val lastName: String,
)
