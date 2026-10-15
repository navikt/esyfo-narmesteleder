package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.Ident.Companion.GRUPPE_IDENT_FNR
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.narmestelederbehov.application.BehovPersonName
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookupResult
import no.nav.syfo.platform.upstream.UpstreamResult

class PdlEmployeeNameLookup(private val client: PdlClient) : EmployeeNameLookup {
    override suspend fun find(employeeIdent: PersonIdent): EmployeeNameLookupResult = when (val result = client.getPerson(employeeIdent.value)) {
        is UpstreamResult.Failure -> EmployeeNameLookupResult.Unavailable(result.failure)

        is UpstreamResult.Success -> {
            val name = result.value?.toEmployeeName()
            if (name == null) EmployeeNameLookupResult.NotFound else EmployeeNameLookupResult.Found(name)
        }
    }
}

private fun PdlPerson.toEmployeeName(): BehovPersonName? {
    val name = person.navn?.firstOrNull() ?: return null
    if (folkeregisterident() == null) return null
    return BehovPersonName(firstName = name.fornavn, middleName = name.mellomnavn, lastName = name.etternavn)
}

private fun PdlPerson.folkeregisterident(): PersonIdent? = identer
    .firstOrNull { it.gruppe == GRUPPE_IDENT_FNR }
    ?.let { PersonIdent(it.ident) }
