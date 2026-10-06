package no.nav.syfo.narmestelederstatistikk.application

import no.nav.syfo.ident.OrganizationNumber

fun interface NarmestelederstatistikkRepository {
    suspend fun countFor(organizationNumber: OrganizationNumber): Narmestelederstatistikk
}
