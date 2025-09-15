package no.nav.syfo.narmesteleder.api.v1

import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt
import no.nav.syfo.narmesteleder.kafka.model.NlResponse
import no.nav.syfo.narmesteleder.kafka.model.Sykmeldt

data class NarmesteLederRelasjonerWrite(
    val sykmeldtFnr: String,
    val organisasjonsnummer: String,
    val leder: Leder,
) {
    fun toNlResponse(): NlResponse = NlResponse(
        orgnummer = organisasjonsnummer, leder = leder, sykmeldt = Sykmeldt(
            fnr = sykmeldtFnr, navn = "PLACEHOLDER"
        )
    )

}
