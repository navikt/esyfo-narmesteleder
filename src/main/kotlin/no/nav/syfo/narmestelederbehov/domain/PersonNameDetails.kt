package no.nav.syfo.narmestelederbehov.domain

data class PersonNameDetails(
    val firstName: String,
    val primaryLastName: String,
    val middleName: String? = null,
    val registeredNames: List<RegisteredName>,
)

data class RegisteredName(
    val lastName: String,
    val middleName: String? = null,
)
