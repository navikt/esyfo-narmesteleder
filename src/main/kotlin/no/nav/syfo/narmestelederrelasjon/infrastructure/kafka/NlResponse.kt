package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import no.nav.syfo.pdl.Person

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
) {
    fun updateFromPerson(person: Person): Leder {
        with(person.name) {
            return Leder(
                fnr = person.nationalIdentificationNumber.value,
                fornavn = listOfNotNull(fornavn, mellomnavn).joinToString(" "),
                etternavn = etternavn,
                mobil = mobil,
                epost = epost,
            )
        }
    }
}
