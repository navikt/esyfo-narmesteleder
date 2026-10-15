package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Employee

fun interface ActiveSykmeldingLookup {
    suspend fun hasActiveSykmelding(employee: Employee): Boolean
}
