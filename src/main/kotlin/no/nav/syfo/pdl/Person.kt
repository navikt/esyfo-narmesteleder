package no.nav.syfo.pdl

import no.nav.syfo.pdl.client.Navn

data class Person(
    val name: Navn,
    val nationalIdentificationNumber: String,
)
