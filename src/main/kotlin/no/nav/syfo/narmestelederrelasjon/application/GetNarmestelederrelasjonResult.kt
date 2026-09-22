package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPersonName

sealed interface GetNarmestelederrelasjonResult {
    data class Found(
        val relation: Narmestelederrelasjon,
        val employeeName: RelationPersonName,
        val organizationName: String,
    ) : GetNarmestelederrelasjonResult
    data object NotFound : GetNarmestelederrelasjonResult
}
