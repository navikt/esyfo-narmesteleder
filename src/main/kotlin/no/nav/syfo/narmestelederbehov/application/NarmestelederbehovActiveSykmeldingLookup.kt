package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Employee

fun interface NarmestelederbehovActiveSykmeldingLookup {
    suspend fun hasActiveSykmelding(employee: Employee): Boolean
}
