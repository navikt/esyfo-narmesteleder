package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.narmesteleder.kafka.model.Leder

data class Manager(
    val nationalIdentificationNumber: String,
    val mobile: String,
    val email: String,
    val firstName: String,
    val lastName: String,
) {
    fun toLeder() = Leder(
        fnr = nationalIdentificationNumber,
        mobil = mobile,
        epost = email,
        fornavn = firstName,
        etternavn = lastName,
    )
}
