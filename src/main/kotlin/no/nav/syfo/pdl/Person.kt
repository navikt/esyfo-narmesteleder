package no.nav.syfo.pdl

import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.pdl.client.Foedselsdato
import no.nav.syfo.pdl.client.Navn

data class Person(
    val name: Navn,
    val names: List<Navn> = listOf(name),
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val dateOfBirth: Foedselsdato? = null,
) {
    val hasParallelNames: Boolean = names.size > 1
}
