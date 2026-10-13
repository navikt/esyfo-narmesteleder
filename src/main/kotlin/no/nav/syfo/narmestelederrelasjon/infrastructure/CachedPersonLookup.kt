package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.application.PersonLookupResult

class CachedPersonLookup(
    private val delegate: PersonLookup,
    private val cache: PersonDetailsCache,
) : PersonLookup {
    override suspend fun find(personIdent: PersonIdent): PersonLookupResult {
        cache.get(personIdent)?.let { return PersonLookupResult.Found(it) }
        return delegate.find(personIdent).also {
            if (it is PersonLookupResult.Found) cache.put(personIdent, it.person)
        }
    }
}
