package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt

data class LinemanagerRevoke(
    val employeeIdentificationNumber: PersonalIdentificationNumber,
    val orgNumber: OrganizationNumber,
    val lastName: String,
) {
    fun toNlAvbrutt(): NlAvbrutt = NlAvbrutt(
        orgnummer = orgNumber.value,
        sykmeldtFnr = employeeIdentificationNumber.value,
    )
}
