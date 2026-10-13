package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.application.PersonLookupResult
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.platform.upstream.UpstreamResult

class PdlPersonLookup(private val client: PdlClient) : PersonLookup {
    override suspend fun find(personIdent: PersonIdent): PersonLookupResult = when (val result = client.getPerson(personIdent.value)) {
        is UpstreamResult.Failure -> PersonLookupResult.Unavailable(result.failure)
        is UpstreamResult.Success -> {
            val person = result.value?.toPersonDetails()
            if (person == null) PersonLookupResult.NotFound else PersonLookupResult.Found(person)
        }
    }
}

private fun PdlPerson.toPersonDetails(): PersonDetails? {
    val names = person.navn.orEmpty()
    val currentName = names.firstOrNull() ?: return null
    val resolvedIdent = folkeregisterident() ?: return null
    return PersonDetails(resolvedIdent, currentName.toPersonNameDetails(names))
}

private fun PdlPerson.folkeregisterident(): PersonIdent? = identer
    .firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }
    ?.let { PersonIdent(it.ident) }

private fun Navn.toPersonNameDetails(registeredNames: List<Navn>) = PersonNameDetails(
    firstName = fornavn,
    middleName = mellomnavn,
    lastName = etternavn,
    registeredNames = registeredNames.map { RegisteredName(it.etternavn, it.mellomnavn) },
)
