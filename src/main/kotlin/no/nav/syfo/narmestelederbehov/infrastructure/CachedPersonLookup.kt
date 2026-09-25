package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.PersonDetails
import no.nav.syfo.narmestelederbehov.application.PersonLookup

class CachedPersonLookup(
    private val delegate: PersonLookup,
    private val cache: PersonDetailsCache,
) : PersonLookup {
    override suspend fun find(personIdent: PersonIdent): PersonDetails? = cache.get(personIdent) ?: delegate.find(personIdent)?.also { cache.put(personIdent, it) }
}
