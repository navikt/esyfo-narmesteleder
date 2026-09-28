package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlAvbrutt

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
