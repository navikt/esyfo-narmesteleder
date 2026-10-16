package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.platform.upstream.UpstreamFailure
import java.time.LocalDate

data class EmployeePerson(val name: BehovPersonName, val birthDate: LocalDate?)

fun interface EmployeePersonLookup {
    suspend fun find(employeeIdent: PersonIdent): EmployeePersonLookupResult
}

sealed interface EmployeePersonLookupResult {
    data class Found(val person: EmployeePerson) : EmployeePersonLookupResult
    data object NotFound : EmployeePersonLookupResult
    data class Unavailable(val failure: UpstreamFailure) : EmployeePersonLookupResult
}
