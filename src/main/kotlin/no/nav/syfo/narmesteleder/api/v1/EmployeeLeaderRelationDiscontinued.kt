package no.nav.syfo.narmesteleder.api.v1

import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt

data class EmployeeLeaderRelationDiscontinued(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
) {
    fun toNlAvbrutt(): NlAvbrutt = NlAvbrutt(
        orgnummer = orgnumber,
        sykmeldtFnr = employeeIdentificationNumber,
    )
}
