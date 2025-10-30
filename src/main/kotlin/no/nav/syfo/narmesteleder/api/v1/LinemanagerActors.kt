package no.nav.syfo.narmesteleder.api.v1

import kotlinx.serialization.Serializable
import no.nav.syfo.pdl.Person

@Serializable
data class LinemanagerActors(
    val manager: Person,
    val employee: Person,
)
