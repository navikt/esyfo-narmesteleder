package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.platform.upstream.UpstreamFailure

sealed interface GetNarmestelederrelasjonResult {
    data class Found(
        val relation: Narmestelederrelasjon,
        val organizationName: String?,
    ) : GetNarmestelederrelasjonResult
    data class NotFound(
        val reason: NotFoundReason,
        val denialReason: DenialReason? = null,
    ) : GetNarmestelederrelasjonResult
    data class UpstreamUnavailable(val failure: UpstreamFailure) : GetNarmestelederrelasjonResult

    enum class NotFoundReason {
        INVALID_ID,
        RELATION_NOT_FOUND,
        RELATION_INACTIVE,
        ACCESS_DENIED,
        NO_ACTIVE_SYKMELDING,
    }
}
