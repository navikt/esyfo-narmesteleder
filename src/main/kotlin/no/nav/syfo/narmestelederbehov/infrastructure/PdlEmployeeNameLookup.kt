package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.GetPersonResponse
import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlRequestException
import no.nav.syfo.integration.pdl.PdlResourceNotFoundException
import no.nav.syfo.integration.pdl.ResponseData
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup

class PdlEmployeeNameLookup(private val client: PdlClient) : EmployeeNameLookup {
    override suspend fun find(employeeIdent: PersonIdent): BehovPersonName? = fetchPerson(employeeIdent)?.toEmployeeName()

    private suspend fun fetchPerson(employeeIdent: PersonIdent): ResponseData? {
        val response = try {
            client.getPerson(employeeIdent.value)
        } catch (_: PdlResourceNotFoundException) {
            return null
        }
        return response.requireData()
    }
}

private fun GetPersonResponse.requireData(): ResponseData = data ?: throw PdlRequestException("Unexpected response from upstream service")

private fun ResponseData.toEmployeeName(): BehovPersonName? {
    val name = person?.navn?.firstOrNull() ?: return null
    if (folkeregisterident() == null) return null
    return BehovPersonName(firstName = name.fornavn, middleName = name.mellomnavn, lastName = name.etternavn)
}

private fun ResponseData.folkeregisterident(): PersonIdent? = identer?.identer
    ?.firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }
    ?.let { PersonIdent(it.ident) }
