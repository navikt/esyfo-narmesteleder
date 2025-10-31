package no.nav.syfo.narmesteleder.domain

data class LinemanagerRequirementWrite(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val managerIdentificationNumber: String,
    val leesahStatus: String,
)
