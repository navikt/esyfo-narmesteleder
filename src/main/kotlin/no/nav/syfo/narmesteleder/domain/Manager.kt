package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.narmesteleder.kafka.model.Leder
import no.nav.syfo.pdl.Person

data class Manager(
    val nationalIdentificationNumber: String,
    val lastName: String,
    val email: String,
    val mobile: String,
) {
    fun toLeder(person: Person) = Leder(
        fnr = nationalIdentificationNumber,
        mobil = mobile,
        epost = email,
        fornavn = person.name.fornavn,
        etternavn = person.name.etternavn,
    )
}
