package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookup
import no.nav.syfo.narmestelederbehov.application.EmployeeNameLookupResult
import no.nav.syfo.platform.upstream.UpstreamResult

class PdlEmployeeNameLookup(private val client: PdlClient) : EmployeeNameLookup {
    override suspend fun find(employeeIdent: PersonIdent): EmployeeNameLookupResult = when (val result = client.getPerson(employeeIdent.value)) {
        is UpstreamResult.Failure -> EmployeeNameLookupResult.Unavailable(result.failure)
        is UpstreamResult.Success -> {
            val name = result.value?.toBehovPersonName()
            if (name == null) EmployeeNameLookupResult.NotFound else EmployeeNameLookupResult.Found(name)
        }
    }
}
