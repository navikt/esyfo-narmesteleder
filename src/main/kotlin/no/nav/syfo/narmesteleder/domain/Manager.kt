package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.Leder
import no.nav.syfo.pdl.Person

data class Manager(
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val lastName: String,
    val email: String,
    val mobile: String,
) {
    fun toLeder(person: Person) = Leder(
        fnr = nationalIdentificationNumber.value,
        mobil = mobile,
        epost = email,
        fornavn = person.name.fornavn,
        etternavn = person.name.etternavn,
    )
}
