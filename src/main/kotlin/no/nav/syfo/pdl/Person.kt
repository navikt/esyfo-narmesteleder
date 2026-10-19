package no.nav.syfo.pdl

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.Foedselsdato
import no.nav.syfo.integration.pdl.Navn

data class Person(
    val name: Navn,
    val nationalIdentificationNumber: PersonIdent,
    val dateOfBirth: Foedselsdato? = null,
)
