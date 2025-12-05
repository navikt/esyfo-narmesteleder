package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt

data class LinemanagerRevoke(
    val employeeIdentificationNumber: String,
    val orgNumber: String,
    val lastName: String,
) {
    fun toNlAvbrutt(): NlAvbrutt = NlAvbrutt(
        orgnummer = orgNumber,
        sykmeldtFnr = employeeIdentificationNumber,
    )
}
