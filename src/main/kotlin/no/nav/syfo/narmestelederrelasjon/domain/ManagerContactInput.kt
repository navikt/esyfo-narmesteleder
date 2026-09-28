package no.nav.syfo.narmestelederrelasjon.domain

import no.nav.syfo.ident.PersonIdent

data class ManagerContactInput(
    val personIdent: PersonIdent,
    val lastName: String,
    val email: String,
    val mobile: String,
)

fun ManagerContactInput.normalize(): ManagerContactNormalization = NormalizedManagerContact.from(this)
