package no.nav.syfo.narmesteleder.kafka.model

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
) {
    companion object {
        fun from(person: Person): Sykmeldt {
            with(person.navn) {
                return Sykmeldt(
                    fnr = person.fnr,
                    navn = listOfNotNull(fornavn, mellomnavn, etternavn).joinToString(" "),
                )
            }
        }
    }
}

data class Leder(
    val fnr: String,
    val mobil: String,
    val epost: String,
    val fornavn: String,
    val etternavn: String,
) {
    fun updateFromPerson(person: Person): Leder {
        with(person.navn) {
            return Leder(
                fnr = person.fnr,
                fornavn = listOfNotNull(fornavn, mellomnavn).joinToString(" "),
                etternavn = etternavn,
                mobil = mobil,
                epost = epost,
            )
        }
    }
}
