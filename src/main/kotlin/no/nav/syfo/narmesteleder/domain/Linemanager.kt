package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

data class Linemanager(
    val employeeIdentificationNumber: String,
    val orgNumber: String,
    val manager: Manager,
) {
    fun toNlResponse(): NlResponse = NlResponse(
        orgnummer = orgNumber,
        leder = manager.toLeder(),
        sykmeldt = Sykmeldt(
            fnr = employeeIdentificationNumber,
            navn = "PLACEHOLDER"
        )
    )
}
