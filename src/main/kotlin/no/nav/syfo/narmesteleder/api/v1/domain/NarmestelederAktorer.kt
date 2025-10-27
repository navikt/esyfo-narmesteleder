package no.nav.syfo.narmesteleder.api.v1.domain

import no.nav.syfo.pdl.Person

data class NarmestelederAktorer(
    val leader: Person,
    val employee: Person,
)
