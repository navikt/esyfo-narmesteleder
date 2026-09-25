package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederbehov.application.PersonDetails
import no.nav.syfo.narmestelederbehov.application.PersonLookup
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederbehov.domain.RegisteredName
import no.nav.syfo.pdl.client.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException

class PdlPersonLookup(private val client: PdlClient) : PersonLookup {
    override suspend fun find(personIdent: PersonIdent): PersonDetails? {
        val response = try {
            client.getPerson(personIdent.value)
        } catch (_: PdlResourceNotFoundException) {
            return null
        }
        val data = response.data ?: throw PdlRequestException("Unexpected response from upstream service")
        val names = data.person?.navn.orEmpty()
        val currentName = names.firstOrNull() ?: return null
        val resolvedIdent = data.identer?.identer?.firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }?.ident ?: return null

        return PersonDetails(
            PersonIdent(resolvedIdent),
            PersonNameDetails(
                firstName = currentName.fornavn,
                middleName = currentName.mellomnavn,
                lastName = currentName.etternavn,
                registeredNames = names.map { RegisteredName(it.etternavn, it.mellomnavn) },
            ),
        )
    }
}
