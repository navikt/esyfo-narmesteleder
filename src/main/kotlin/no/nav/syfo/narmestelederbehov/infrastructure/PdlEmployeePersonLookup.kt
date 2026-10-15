package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.narmestelederbehov.application.EmployeePerson
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookup
import no.nav.syfo.narmestelederbehov.application.EmployeePersonLookupResult
import no.nav.syfo.platform.upstream.UpstreamResult

class PdlEmployeePersonLookup(private val client: PdlClient) : EmployeePersonLookup {
    override suspend fun find(employeeIdent: PersonIdent): EmployeePersonLookupResult = when (val result = client.getPerson(employeeIdent.value)) {
        is UpstreamResult.Failure -> EmployeePersonLookupResult.Unavailable(result.failure)
        is UpstreamResult.Success -> {
            val person = result.value?.toEmployeePerson()
            if (person == null) EmployeePersonLookupResult.NotFound else EmployeePersonLookupResult.Found(person)
        }
    }
}

private fun PdlPerson.toEmployeePerson(): EmployeePerson? = toBehovPersonName()?.let { name ->
    EmployeePerson(name = name, birthDate = person.foedselsdato?.firstOrNull()?.foedselsdato)
}
