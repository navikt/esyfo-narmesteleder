package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.application.establish.EmploymentResult
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactInput
import no.nav.syfo.narmestelederrelasjon.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamFailure

data class FulfillNarmestelederbehovCommand(
    val behovId: NarmestelederbehovId,
    val manager: ManagerContactInput,
    val accessSubject: OrganizationAccessSubject,
)

data class FulfilledNarmestelederbehov(
    val relationSource: RelationSource,
    val dialogCompletion: DialogportenCompletionAttempt,
)

sealed interface FulfillNarmestelederbehovError {
    data class InvalidManagerContactDetails(
        val issues: List<ManagerContactValidationIssue>,
    ) : FulfillNarmestelederbehovError
    data object NotFound : FulfillNarmestelederbehovError
    data object BehovMissingAfterPublication : FulfillNarmestelederbehovError
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : FulfillNarmestelederbehovError
    data class NoActiveSykmelding(val organizationNumber: OrganizationNumber) : FulfillNarmestelederbehovError
    data class NoEmployment(val reason: EmploymentResult.Missing) : FulfillNarmestelederbehovError
    data class UpstreamUnavailable(val failure: UpstreamFailure) : FulfillNarmestelederbehovError
    data object PersonNotFound : FulfillNarmestelederbehovError
    data object ManagerNameMismatch : FulfillNarmestelederbehovError
}
