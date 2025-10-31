package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt

data class LinemanagerRevoke(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
) {
    fun toNlAvbrutt(): NlAvbrutt = NlAvbrutt(
        orgnummer = orgnumber,
        sykmeldtFnr = employeeIdentificationNumber,
    )
}
