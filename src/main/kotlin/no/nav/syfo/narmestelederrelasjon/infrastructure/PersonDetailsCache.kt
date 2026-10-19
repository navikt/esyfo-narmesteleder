package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails

interface PersonDetailsCache {
    fun get(personIdent: PersonalIdentificationNumber): PersonDetails?
    fun put(personIdent: PersonalIdentificationNumber, personDetails: PersonDetails)
}
