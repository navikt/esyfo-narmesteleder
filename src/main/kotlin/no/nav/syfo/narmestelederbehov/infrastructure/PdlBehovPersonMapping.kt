package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.narmestelederbehov.application.BehovPersonName

/**
 * Returns null when PDL has no name or no folkeregisterident.
 *
 * @throws IllegalArgumentException when the folkeregisterident is malformed.
 */
internal fun PdlPerson.toBehovPersonName(): BehovPersonName? {
    val name = person.navn?.firstOrNull() ?: return null
    if (folkeregisterident() == null) return null
    return BehovPersonName(firstName = name.fornavn, middleName = name.mellomnavn, lastName = name.etternavn)
}

private fun PdlPerson.folkeregisterident(): PersonIdent? = identer
    .firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }
    ?.let { PersonIdent(it.ident) }
