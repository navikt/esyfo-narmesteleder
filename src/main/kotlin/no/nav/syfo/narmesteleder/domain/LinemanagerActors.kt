package no.nav.syfo.narmesteleder.domain

import no.nav.syfo.pdl.Person

data class LinemanagerActors(
    val manager: Person,
    val employee: Person,
)
data class Organization(
    val orgnumber: String,
    val name: String?,
)
