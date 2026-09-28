package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails

interface PersonDetailsCache {
    fun get(personIdent: PersonIdent): PersonDetails?
    fun put(personIdent: PersonIdent, personDetails: PersonDetails)
}
