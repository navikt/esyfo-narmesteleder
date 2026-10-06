package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.GetPersonResponse
import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlRequestException
import no.nav.syfo.integration.pdl.PdlResourceNotFoundException
import no.nav.syfo.integration.pdl.ResponseData
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookup
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName

class PdlPersonLookup(private val client: PdlClient) : PersonLookup {
    override suspend fun find(personIdent: PersonIdent): PersonDetails? = fetchPerson(personIdent)?.toPersonDetails()

    private suspend fun fetchPerson(personIdent: PersonIdent): ResponseData? {
        val response = try {
            client.getPerson(personIdent.value)
        } catch (_: PdlResourceNotFoundException) {
            return null
        }
        return response.requireData()
    }
}

private fun GetPersonResponse.requireData(): ResponseData = data ?: throw PdlRequestException("Unexpected response from upstream service")

private fun ResponseData.toPersonDetails(): PersonDetails? {
    val names = person?.navn.orEmpty()
    val currentName = names.firstOrNull() ?: return null
    val resolvedIdent = folkeregisterident() ?: return null
    return PersonDetails(resolvedIdent, currentName.toPersonNameDetails(names))
}

private fun ResponseData.folkeregisterident(): PersonIdent? = identer?.identer
    ?.firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }
    ?.let { PersonIdent(it.ident) }

private fun Navn.toPersonNameDetails(registeredNames: List<Navn>) = PersonNameDetails(
    firstName = fornavn,
    middleName = mellomnavn,
    lastName = etternavn,
    registeredNames = registeredNames.map { RegisteredName(it.etternavn, it.mellomnavn) },
)
