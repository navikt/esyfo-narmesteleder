package no.nav.syfo.narmesteleder.api.v1

import no.nav.syfo.narmesteleder.kafka.model.NlAvbrutt

data class NarmestelederRelasjonAvkreft(
    val sykmeldtFnr: String,
    val organisasjonsnummer: String,
) {
    fun toNlAvbrutt(): NlAvbrutt = NlAvbrutt(
        orgnummer = organisasjonsnummer,
        sykmeldtFnr = sykmeldtFnr,
    )
}
