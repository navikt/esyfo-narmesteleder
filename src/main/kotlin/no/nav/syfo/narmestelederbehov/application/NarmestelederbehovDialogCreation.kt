package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId

/** Creates the Dialogporten dialog for a stored behov. Failures are handled by the adapter and leave the behov stored. */
fun interface NarmestelederbehovDialogCreation {
    suspend fun create(id: NarmestelederbehovId, behov: NewNarmestelederbehov)
}
