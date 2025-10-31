package no.nav.syfo.narmesteleder.api.v1

import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

data class Linemanager(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
    val manager: Manager,
) {
    fun toNlResponse(): NlResponse = NlResponse(
        orgnummer = orgnumber,
        leder = manager.toLeder(),
        sykmeldt = Sykmeldt(
            fnr = employeeIdentificationNumber,
            navn = "PLACEHOLDER"
        )
    )
}
