package no.nav.syfo.narmesteleder.kafka.model

data class NlResponse(
    val orgnummer: String,
    val utbetalesLonn: Boolean? = null,
    val leder: Leder,
    val sykmeldt: Sykmeldt,
)
data class Sykmeldt(
    val fnr: String,
    val navn: String,
)
data class Leder(
    val fnr: String,
    val mobil: String,
    val epost: String,
    val fornavn: String,
    val etternavn: String,
)
