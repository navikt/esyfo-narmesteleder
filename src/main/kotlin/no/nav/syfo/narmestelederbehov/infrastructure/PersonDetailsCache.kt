package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.PersonDetails

interface PersonDetailsCache {
    fun get(personIdent: PersonIdent): PersonDetails?
    fun put(personIdent: PersonIdent, personDetails: PersonDetails)
}
