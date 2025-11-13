package no.nav.syfo.narmesteleder.domain

data class Linemanager(
    val employeeIdentificationNumber: String,
    val orgNumber: String,
    val manager: Manager,
)
