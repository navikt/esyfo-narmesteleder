package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmestelederbehov.domain.ManagerContactInput
import no.nav.syfo.narmestelederbehov.domain.ManagerContactNormalization
import no.nav.syfo.narmestelederbehov.domain.ManagerContactValidationIssue
import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.domain.matchManagerLastName
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
) {
    suspend fun execute(command: FulfillNarmestelederbehovCommand): FulfillNarmestelederbehovResult {
        val manager = when (val normalization = command.manager.normalize()) {
            is ManagerContactNormalization.Valid -> normalization.manager
            is ManagerContactNormalization.Invalid -> {
                return FulfillNarmestelederbehovResult.InvalidManagerContactDetails(normalization.issues).log()
            }
        }
        val behov = behovRepository.findForFulfillment(command.behovId)
            ?: return FulfillNarmestelederbehovResult.NotFound.log()

        if (organizationAccess.evaluate(command.accessSubject, behov.employee.organizationNumber) == OrganizationAccessResult.Denied) {
            return FulfillNarmestelederbehovResult.AccessDenied.log()
        }
        if (!activeSykmeldingLookup.hasActiveSykmelding(behov.employee.personIdent, behov.employee.organizationNumber)) {
            return FulfillNarmestelederbehovResult.NoActiveSykmelding.log()
        }
        if (!employmentLookup.hasEmployment(behov.employee.personIdent, behov.employee.organizationNumber)) {
            return FulfillNarmestelederbehovResult.NoEmployment.log()
        }

        val employee = personLookup.find(behov.employee.personIdent)
            ?: return FulfillNarmestelederbehovResult.PersonNotFound.log()
        val managerPerson = personLookup.find(manager.personIdent)
            ?: return FulfillNarmestelederbehovResult.PersonNotFound.log()
        val managerNameMatch = managerPerson.name.matchManagerLastName(manager.lastName)
        if (managerNameMatch is ManagerLastNameMatch.NoMatch) {
            return FulfillNarmestelederbehovResult.ManagerNameMismatch(managerNameMatch).log()
        }

        val relationSource = command.accessSubject.relationSource()
        establishNarmestelederrelasjon.establish(
            EstablishNarmestelederrelasjonCommand(
                employee = RelationPerson(
                    personIdent = employee.personIdent,
                    firstName = employee.name.firstName,
                    middleName = employee.name.middleName,
                    lastName = employee.name.primaryLastName,
                ),
                manager = RelationManager(
                    personIdent = manager.personIdent,
                    firstName = managerPerson.name.firstName,
                    middleName = managerPerson.name.middleName,
                    lastName = managerPerson.name.primaryLastName,
                    email = manager.email.value,
                    mobile = manager.mobile.value,
                ),
                organizationNumber = behov.employee.organizationNumber,
                source = relationSource,
            ),
        )
        behovRepository.markFulfilled(behov.id)
        val dialogCompletion = dialog.attemptCompletion(behov.id)
        return FulfillNarmestelederbehovResult.Fulfilled(
            relationSource = relationSource,
            dialogCompletion = dialogCompletion,
            managerNameMatch = managerNameMatch,
        )
            .log()
    }

    private fun <T : FulfillNarmestelederbehovResult> T.log(): T = also { result ->
        when (result) {
            is FulfillNarmestelederbehovResult.Fulfilled -> logger.event(fulfillmentCompleted, result)
            else -> logger.event(fulfillmentRejected, result)
        }
    }

    private companion object {
        val logger = applicationLogger(FulfillNarmestelederbehovUseCase::class.java)
    }
}

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
    data object AccessDenied : FulfillNarmestelederbehovResult
    data object NoActiveSykmelding : FulfillNarmestelederbehovResult
    data object NoEmployment : FulfillNarmestelederbehovResult
    data object PersonNotFound : FulfillNarmestelederbehovResult
    data class ManagerNameMismatch(
        val managerNameMatch: ManagerLastNameMatch.NoMatch,
    ) : FulfillNarmestelederbehovResult
}

private fun OrganizationAccessSubject.relationSource(): RelationSource = when (this) {
    is OrganizationAccessSubject.LpsSystemUser -> RelationSource.LPS
    is OrganizationAccessSubject.PersonnelManager -> RelationSource.PERSONNEL_MANAGER
}
