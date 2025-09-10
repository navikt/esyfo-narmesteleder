package no.nav.syfo.no.nav.syfo.narmesteleder.api.v1

data class NarmesteLederRelasjonerWrite(
    val sykmeldtFnr: String,
    val organisasjonsnummer: String,
    val leder: Leder?,
)

data class Leder(
    val fnr: String,
    val mobil: String,
    val epost: String,
    val fornavn: String,
    val etternavn: String,
)
