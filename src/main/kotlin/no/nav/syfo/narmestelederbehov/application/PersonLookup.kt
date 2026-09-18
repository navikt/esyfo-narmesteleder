package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails

fun interface PersonLookup {
    suspend fun find(personIdent: PersonIdent): PersonNameDetails?
}
