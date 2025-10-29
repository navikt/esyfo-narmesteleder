package no.nav.syfo.narmesteleder.api.v1

import no.nav.syfo.pdl.Person

data class LinemanagerActors(
    val manager: Person,
    val employee: Person,
)
