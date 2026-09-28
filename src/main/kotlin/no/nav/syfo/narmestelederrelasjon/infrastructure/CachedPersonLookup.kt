package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup

class CachedPersonLookup(
    private val delegate: PersonLookup,
    private val cache: PersonDetailsCache,
) : PersonLookup {
    override suspend fun find(personIdent: PersonIdent): PersonDetails? = cache.get(personIdent) ?: delegate.find(personIdent)?.also { cache.put(personIdent, it) }
}
