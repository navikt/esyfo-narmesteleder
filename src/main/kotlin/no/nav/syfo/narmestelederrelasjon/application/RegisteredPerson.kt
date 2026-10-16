package no.nav.syfo.narmestelederrelasjon.application

import java.time.LocalDate

/** Person details from the person register, stored in the `RelationPerson` projection. */
data class RegisteredPerson(
    val firstName: String,
    val middleName: String?,
    val lastName: String,
    val birthDate: LocalDate?,
)
