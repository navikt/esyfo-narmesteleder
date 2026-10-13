package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface EmployeeNameLookup {
    suspend fun find(employeeIdent: PersonIdent): EmployeeNameLookupResult
}

sealed interface EmployeeNameLookupResult {
    data class Found(val name: BehovPersonName) : EmployeeNameLookupResult
    data object NotFound : EmployeeNameLookupResult
    data class Unavailable(val failure: UpstreamFailure) : EmployeeNameLookupResult
}
