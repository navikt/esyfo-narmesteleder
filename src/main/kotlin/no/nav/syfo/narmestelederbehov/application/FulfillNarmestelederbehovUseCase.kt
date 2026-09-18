package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.ManagerContactInput
import no.nav.syfo.narmestelederbehov.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederbehov.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.domain.matchesManagerLastName
import no.nav.syfo.narmestelederbehov.domain.normalize
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjonCommand
import no.nav.syfo.narmestelederrelasjon.application.RelationManager
import no.nav.syfo.narmestelederrelasjon.application.RelationPerson
import no.nav.syfo.narmestelederrelasjon.application.RelationSource
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject

class FulfillNarmestelederbehovUseCase(
    private val behovRepository: NarmestelederbehovRepository,
    private val organizationAccess: OrganizationAccess,
    private val activeSykmeldingLookup: ActiveSykmeldingLookup,
    private val employmentLookup: EmploymentLookup,
    private val personLookup: PersonLookup,
    private val establishNarmestelederrelasjon: EstablishNarmestelederrelasjon,
    private val dialog: NarmestelederbehovDialog,
    private val outcomeLogger: FulfillNarmestelederbehovOutcomeLogger,
) {
    suspend fun execute(command: FulfillNarmestelederbehovCommand): FulfillNarmestelederbehovResult {
        val manager = when (val normalization = command.manager.normalize()) {
            is ManagerContactNormalization.Valid -> normalization.manager
            is ManagerContactNormalization.Invalid -> {
                return FulfillNarmestelederbehovResult.InvalidManagerContactDetails(normalization.issues)
                    .log(FulfillNarmestelederbehovOutcome.InvalidManagerContactDetails)
            }
        }
        val behov = behovRepository.findForFulfillment(command.behovId)
            ?: return FulfillNarmestelederbehovResult.NotFound.log(FulfillNarmestelederbehovOutcome.NotFound)

        if (organizationAccess.evaluate(command.accessSubject, behov.employee.organizationNumber) == OrganizationAccessResult.Denied) {
            return FulfillNarmestelederbehovResult.AccessDenied.log(FulfillNarmestelederbehovOutcome.AccessDenied)
        }
        if (!activeSykmeldingLookup.hasActiveSykmelding(behov.employee.personIdent, behov.employee.organizationNumber)) {
            return FulfillNarmestelederbehovResult.NoActiveSykmelding.log(FulfillNarmestelederbehovOutcome.NoActiveSykmelding)
        }
        if (!employmentLookup.hasEmployment(behov.employee.personIdent, behov.employee.organizationNumber)) {
            return FulfillNarmestelederbehovResult.NoEmployment.log(FulfillNarmestelederbehovOutcome.NoEmployment)
        }

        val employee = personLookup.find(behov.employee.personIdent)
            ?: return FulfillNarmestelederbehovResult.PersonNotFound.log(FulfillNarmestelederbehovOutcome.PersonNotFound)
        val managerPerson = personLookup.find(manager.personIdent)
            ?: return FulfillNarmestelederbehovResult.PersonNotFound.log(FulfillNarmestelederbehovOutcome.PersonNotFound)
        if (!managerPerson.matchesManagerLastName(manager.lastName)) {
            return FulfillNarmestelederbehovResult.ManagerNameMismatch.log(FulfillNarmestelederbehovOutcome.ManagerNameMismatch)
        }

        val relationSource = command.accessSubject.relationSource()
        establishNarmestelederrelasjon.establish(
            EstablishNarmestelederrelasjonCommand(
                employee = RelationPerson(
                    personIdent = behov.employee.personIdent,
                    firstName = employee.firstName,
                    lastName = employee.primaryLastName,
                ),
                manager = RelationManager(
                    personIdent = manager.personIdent,
                    firstName = managerPerson.firstName,
                    lastName = managerPerson.primaryLastName,
                    email = manager.email.value,
                    mobile = manager.mobile.value,
                ),
                organizationNumber = behov.employee.organizationNumber,
                source = relationSource,
            ),
        )
        behovRepository.markFulfilled(behov.id)
        val dialogCompletion = dialog.attemptCompletion(behov.id)
        return FulfillNarmestelederbehovResult.Fulfilled.log(
            FulfillNarmestelederbehovOutcome.Fulfilled(
                relationSource = relationSource,
                dialogCompletion = dialogCompletion,
            ),
        )
    }

    private fun <T : FulfillNarmestelederbehovResult> T.log(
        outcome: FulfillNarmestelederbehovOutcome,
    ): T = also { outcomeLogger.log(outcome) }
}

data class FulfillNarmestelederbehovCommand(
    val behovId: NarmestelederbehovId,
    val manager: ManagerContactInput,
    val accessSubject: OrganizationAccessSubject,
)

sealed interface FulfillNarmestelederbehovResult {
    data object Fulfilled : FulfillNarmestelederbehovResult
    data class InvalidManagerContactDetails(
        val issues: List<ManagerContactValidationIssue>,
    ) : FulfillNarmestelederbehovResult
    data object NotFound : FulfillNarmestelederbehovResult
    data object AccessDenied : FulfillNarmestelederbehovResult
    data object NoActiveSykmelding : FulfillNarmestelederbehovResult
    data object NoEmployment : FulfillNarmestelederbehovResult
    data object PersonNotFound : FulfillNarmestelederbehovResult
    data object ManagerNameMismatch : FulfillNarmestelederbehovResult
}

private fun OrganizationAccessSubject.relationSource(): RelationSource = when (this) {
    is OrganizationAccessSubject.LpsSystemUser -> RelationSource.LPS
    is OrganizationAccessSubject.PersonnelManager -> RelationSource.PERSONNEL_MANAGER
}
