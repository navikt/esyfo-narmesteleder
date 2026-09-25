package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmestelederbehov.domain.ManagerContactInput
import no.nav.syfo.narmestelederbehov.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

data class FulfillNarmestelederbehovCommand(
    val behovId: NarmestelederbehovId,
    val manager: ManagerContactInput,
    val accessSubject: OrganizationAccessSubject,
)

sealed interface FulfillNarmestelederbehovResult {
    data class Fulfilled(
        val relationSource: RelationSource,
        val dialogCompletion: DialogportenCompletionAttempt,
        val managerNameMatch: ManagerLastNameMatch,
    ) : FulfillNarmestelederbehovResult
    data class InvalidManagerContactDetails(
        val issues: List<ManagerContactValidationIssue>,
    ) : FulfillNarmestelederbehovResult
    data object NotFound : FulfillNarmestelederbehovResult
    data object BehovMissingAfterPublication : FulfillNarmestelederbehovResult
    data class AccessDenied(val reason: DenialReason, val organizationNumber: OrganizationNumber) : FulfillNarmestelederbehovResult
    data class NoActiveSykmelding(val organizationNumber: OrganizationNumber) : FulfillNarmestelederbehovResult
    data class NoEmployment(val reason: EmploymentResult) : FulfillNarmestelederbehovResult
    data object PersonNotFound : FulfillNarmestelederbehovResult
    data class ManagerNameMismatch(
        val managerNameMatch: ManagerLastNameMatch.NoMatch,
    ) : FulfillNarmestelederbehovResult
}
