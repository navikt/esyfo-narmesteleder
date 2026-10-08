package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId

/** Open statuses are BEHOV_CREATED and DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION. */
fun interface OpenNarmestelederbehovForEmployee {
    suspend fun find(employee: Employee): List<NarmestelederbehovId>
}
