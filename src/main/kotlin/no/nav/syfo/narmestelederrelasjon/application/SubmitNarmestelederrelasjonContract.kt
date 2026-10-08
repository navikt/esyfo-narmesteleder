package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamFailure

data class SubmitNarmestelederrelasjonCommand(
    val employeeIdent: PersonIdent,
    val employeeLastName: String,
    val organizationNumber: OrganizationNumber,
    val manager: ManagerContactInput,
    val accessSubject: OrganizationAccessSubject,
)

sealed interface SubmitNarmestelederrelasjonResult {
    data class InvalidManagerContactDetails(val issues: List<ManagerContactValidationIssue>) : SubmitNarmestelederrelasjonResult
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : SubmitNarmestelederrelasjonResult
    data class Established(val source: RelationSource) : SubmitNarmestelederrelasjonResult
    data class EstablishRejected(val reason: EstablishNarmestelederrelasjonResult) : SubmitNarmestelederrelasjonResult
    data class UpstreamUnavailable(val failure: UpstreamFailure) : SubmitNarmestelederrelasjonResult
}
