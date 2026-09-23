package no.nav.syfo.narmestelederbehov.infrastructure

import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.Event
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.getForOrgnummer
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.IDinesykmeldteService
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.service.validators.NameMatchType
import no.nav.syfo.narmesteleder.service.validators.NameValidator
import no.nav.syfo.narmestelederbehov.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederbehov.application.DialogportenCompletionAttempt
import no.nav.syfo.narmestelederbehov.application.EmploymentLookup
import no.nav.syfo.narmestelederbehov.application.EmploymentResult
import no.nav.syfo.narmestelederbehov.application.ManagerNameValidationMetrics
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovDialog
import no.nav.syfo.narmestelederbehov.application.NarmestelederbehovRepository
import no.nav.syfo.narmestelederbehov.application.PersonDetails
import no.nav.syfo.narmestelederbehov.application.PersonLookup
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch
import no.nav.syfo.narmestelederbehov.domain.Narmestelederbehov
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.narmestelederbehov.domain.PersonNameDetails
import no.nav.syfo.narmestelederbehov.domain.RegisteredName
import no.nav.syfo.pdl.PdlService
import org.slf4j.event.Level

private val dialogportenCompletionFailed = Event<Unit>(
    name = "narmestelederbehov_dialogporten_completion_failed",
    level = Level.WARN,
    message = "Dialogporten completion failed; pending behov remains retryable",
    operation = "fulfill_narmestelederbehov",
)

class DbNarmestelederbehovRepository(private val db: INarmestelederDb) : NarmestelederbehovRepository {
    override suspend fun findForFulfillment(id: NarmestelederbehovId): Narmestelederbehov? = db.findBehovById(id.value)?.let {
        Narmestelederbehov(id, Employee(PersonIdent(it.sykmeldtFnr), OrganizationNumber(it.orgnummer)))
    }

    override suspend fun markFulfilled(id: NarmestelederbehovId) {
        // Re-read after publication, matching the legacy missing-behov failure.
        val behov = db.findBehovById(id.value)
            ?: throw ApiErrorException.NotFoundException("A LinemanagerRequirement was not found")
        db.updateNlBehov(behov.copy(behovStatus = BehovStatus.BEHOV_FULFILLED))
    }
}

class DinesykmeldteActiveSykmeldingLookup(private val service: IDinesykmeldteService) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(personIdent: PersonIdent, organizationNumber: OrganizationNumber) = service.getIsActiveSykmelding(personIdent.value, organizationNumber.value)
}

class AaregEmploymentLookup(private val service: AaregService) : EmploymentLookup {
    override suspend fun findEmployment(personIdent: PersonIdent, organizationNumber: OrganizationNumber): EmploymentResult {
        val employment = service.findArbeidsforholdByPersonIdent(personIdent.value)
        return when {
            employment.isEmpty() -> EmploymentResult.NONE
            employment.getForOrgnummer(organizationNumber.value) == null -> EmploymentResult.NOT_IN_ORGANIZATION
            else -> EmploymentResult.IN_ORGANIZATION
        }
    }
}

class PdlPersonLookup(private val service: PdlService) : PersonLookup {
    override suspend fun find(personIdent: PersonIdent): PersonDetails? {
        val person = try {
            service.getPersonOrThrowApiError(personIdent.value)
        } catch (e: ApiErrorException.BadRequestException) {
            if (e.errorMessage == "Could not find person in PDL") return null
            throw e
        }
        return PersonDetails(
            PersonIdent(person.nationalIdentificationNumber.value),
            PersonNameDetails(
                firstName = person.name.fornavn,
                middleName = person.name.mellomnavn,
                primaryLastName = person.name.etternavn,
                registeredNames = person.names.map { RegisteredName(it.etternavn, it.mellomnavn) },
            ),
        )
    }
}

class LegacyManagerNameValidationMetrics : ManagerNameValidationMetrics {
    override fun record(match: ManagerLastNameMatch) {
        val type = when (match) {
            is ManagerLastNameMatch.Exact -> NameMatchType.EXACT
            is ManagerLastNameMatch.OrthographicVariant -> NameMatchType.ORTHOGRAPHIC_VARIANT
            is ManagerLastNameMatch.Fuzzy -> NameMatchType.FUZZY
            is ManagerLastNameMatch.NoMatch -> NameMatchType.NONE
        }
        val score = when (match) {
            is ManagerLastNameMatch.Fuzzy -> match.score
            is ManagerLastNameMatch.NoMatch -> match.bestFuzzyScore
            else -> null
        }
        NameValidator.recordManagerLastNameMatch(type, match.hasParallelNames, score)
    }
}

class DialogportenNarmestelederbehovDialog(
    private val db: INarmestelederDb,
    private val service: DialogportenService,
) : NarmestelederbehovDialog {
    override suspend fun attemptCompletion(id: NarmestelederbehovId): DialogportenCompletionAttempt {
        try {
            val behov = db.findBehovById(id.value)
                ?: return DialogportenCompletionAttempt.NotApplicable
            if (behov.dialogId == null) return DialogportenCompletionAttempt.NotApplicable
            service.completeFulfilledDialog(behov)
            return DialogportenCompletionAttempt.Completed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.event(dialogportenCompletionFailed, Unit)
            return DialogportenCompletionAttempt.Failed
        }
    }

    private companion object {
        val logger = applicationLogger(DialogportenNarmestelederbehovDialog::class.java)
    }
}
