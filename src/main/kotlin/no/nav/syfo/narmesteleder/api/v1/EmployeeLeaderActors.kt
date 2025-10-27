package no.nav.syfo.narmesteleder.api.v1

import no.nav.syfo.pdl.Person

data class EmployeeLeaderActors(
    val leader: Person,
    val employee: Person,
)
