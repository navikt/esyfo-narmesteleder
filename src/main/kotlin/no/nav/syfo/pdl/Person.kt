package no.nav.syfo.pdl

import no.nav.syfo.integration.pdl.Foedselsdato
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber

data class Person(
    val name: Navn,
    val nationalIdentificationNumber: PersonalIdentificationNumber,
    val dateOfBirth: Foedselsdato? = null,
)
