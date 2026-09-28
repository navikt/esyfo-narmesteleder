package no.nav.syfo.narmestelederrelasjon.domain

data class PersonNameDetails(
    val firstName: String,
    val lastName: String,
    val middleName: String? = null,
    val registeredNames: List<RegisteredName>,
)

data class RegisteredName(
    val lastName: String,
    val middleName: String? = null,
)
