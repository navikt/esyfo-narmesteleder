package no.nav.syfo.narmestelederrelasjon.domain

import no.nav.syfo.ident.PersonalIdentificationNumber

data class ManagerContactInput(
    val personIdent: PersonalIdentificationNumber,
    val lastName: String,
    val email: String,
    val mobile: String,
)

fun ManagerContactInput.normalize(): ManagerContactNormalization = NormalizedManagerContact.from(this)
