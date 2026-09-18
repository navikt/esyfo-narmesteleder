package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId

interface NarmestelederbehovRepository {
    suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov?

    suspend fun markFulfilled(id: NarmestelederbehovId)
}
