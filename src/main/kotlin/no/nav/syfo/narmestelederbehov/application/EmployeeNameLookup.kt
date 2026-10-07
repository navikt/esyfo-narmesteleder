package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.PersonIdent

fun interface EmployeeNameLookup {
    suspend fun find(employeeIdent: PersonIdent): BehovPersonName?
}
