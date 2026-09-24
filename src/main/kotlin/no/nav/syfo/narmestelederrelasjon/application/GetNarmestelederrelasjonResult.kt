package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon

sealed interface GetNarmestelederrelasjonResult {
    data class Found(
        val relation: Narmestelederrelasjon,
        val organizationName: String,
    ) : GetNarmestelederrelasjonResult
    data object NotFound : GetNarmestelederrelasjonResult
    data object Unavailable : GetNarmestelederrelasjonResult
}
