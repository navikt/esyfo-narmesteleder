package no.nav.syfo.narmesteleder.api.v1

import kotlinx.serialization.Serializable
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt

@Serializable
data class LinemanagerRevoke(
    val employeeIdentificationNumber: String,
    val orgnumber: String,
) {
    fun toNlAvbrutt(): NlAvbrutt = NlAvbrutt(
        orgnummer = orgnumber,
        sykmeldtFnr = employeeIdentificationNumber,
    )
}
