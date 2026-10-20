package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmestelederbehov.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop

/**
 * Stores a new behov when the employee has an active sykmelding and no open behov in the organization.
 * A behov whose main organization cannot be resolved is still stored, with an error status and no dialog.
 */
class CreateNarmestelederbehovUseCase(
    private val settings: NarmestelederbehovCreationSettings,
    private val repository: NarmestelederbehovRepository,
    private val activeSykmelding: ActiveSykmeldingLookup,
    private val mainOrganization: EmployerMainOrganizationLookup,
    private val personLookup: EmployeePersonLookup,
    private val dialog: NarmestelederbehovDialogCreation,
    private val metrics: NarmestelederbehovCreationMetrics,
) : CreateNarmestelederbehov {
    private val dialogSender = NarmestelederbehovDialogSender(repository, personLookup, dialog, logger)

    override suspend fun execute(command: CreateNarmestelederbehovCommand): CreateNarmestelederbehovResult {
        ensurePersistenceEnabled().orStop { return it }
        ensureNoOpenBehov(command.employee).orStop { return it }
        ensureActiveSykmelding(command).orStop { return it }
        val resolvedMainOrganization = resolveMainOrganization(command).orStop { return it }
        val behov = newBehov(command, resolvedMainOrganization)
        val id = store(behov).orStop { return it }
        createDialogUnlessDegraded(id, behov)
        return CreateNarmestelederbehovResult.Created(id)
    }

    private fun ensurePersistenceEnabled(): CreateStep<Unit> {
        if (settings.persistenceEnabled) return Step.Proceed
        logger.info("Skipping persistence of LinemanagerRequirement as configured.")
        return Step.Stop(CreateNarmestelederbehovResult.Disabled)
    }

    private suspend fun ensureNoOpenBehov(employee: Employee): CreateStep<Unit> = if (repository.findOpenFor(employee).isEmpty()) {
        Step.Proceed
    } else {
        Step.Stop(alreadyExists())
    }

    private suspend fun ensureActiveSykmelding(command: CreateNarmestelederbehovCommand): CreateStep<Unit> {
        if (command.sykmeldingKnownActive || activeSykmelding.hasActiveSykmelding(command.employee)) return Step.Proceed
        metrics.recordSkippedNoActiveSykmelding()
        logger.info(
            "Not inserting NarmestelederBehovEntity as there is no active sick leave for employee with" +
                " narmestelederId ${command.revokedRelationId}"
        )
        return Step.Stop(CreateNarmestelederbehovResult.NoActiveSykmelding)
    }

    private suspend fun resolveMainOrganization(command: CreateNarmestelederbehovCommand): CreateStep<ResolvedMainOrganization> = when (val source = command.mainOrganization) {
        is MainOrganizationSource.FromSykmelding -> {
            val reported = source.mainOrganizationNumber
            if (reported == null) {
                metrics.recordStoredWithoutMainOrganization()
                logDegraded(command, StoredDegradedReason.SICK_LEAVE_MAIN_ORG_MISSING)
                Step.Continue(ResolvedMainOrganization.missing(BehovStatus.HOVEDENHET_NOT_FOUND))
            } else {
                Step.Continue(ResolvedMainOrganization.found(reported))
            }
        }

        MainOrganizationSource.FromEmployment -> when (val found = mainOrganization.findMainOrganization(command.employee)) {
            is MainOrganizationResult.Found -> Step.Continue(ResolvedMainOrganization.found(found.mainOrganizationNumber))
            MainOrganizationResult.EmploymentMissing -> {
                metrics.recordStoredWithoutEmployment()
                logDegraded(command, StoredDegradedReason.EMPLOYMENT_MISSING)
                Step.Continue(ResolvedMainOrganization.missing(BehovStatus.ARBEIDSFORHOLD_NOT_FOUND))
            }
            MainOrganizationResult.MainOrganizationMissing -> {
                logDegraded(command, StoredDegradedReason.EMPLOYMENT_MAIN_ORG_MISSING)
                Step.Continue(ResolvedMainOrganization.missing(BehovStatus.HOVEDENHET_NOT_FOUND))
            }
            is MainOrganizationResult.Unavailable -> Step.Stop(CreateNarmestelederbehovResult.UpstreamUnavailable(found.failure))
        }
    }

    private fun newBehov(command: CreateNarmestelederbehovCommand, mainOrganization: ResolvedMainOrganization) = NewNarmestelederbehov(
        employee = command.employee,
        mainOrganizationNumber = mainOrganization.number,
        manager = command.manager,
        reason = command.reason,
        status = mainOrganization.status,
        revokedRelationId = command.revokedRelationId,
    )

    private suspend fun store(behov: NewNarmestelederbehov): CreateStep<NarmestelederbehovId> = when (val created = repository.create(behov)) {
        is CreateBehovResult.Created -> {
            logger.info("Inserted NarmestelederBehovEntity with id: ${created.id.value}")
            Step.Continue(created.id)
        }
        CreateBehovResult.AlreadyExists -> Step.Stop(alreadyExists())
    }

    /** A behov stored with an error status has no main organization to address a dialog to. */
    private suspend fun createDialogUnlessDegraded(id: NarmestelederbehovId, behov: NewNarmestelederbehov) {
        if (behov.status in BehovStatus.errorStatusList()) return
        dialogSender.send(id, behov.employee)
    }

    private fun alreadyExists(): CreateNarmestelederbehovResult {
        metrics.recordSkippedAlreadyExists()
        logger.info("Not inserting NarmestelederBehovEntity since one already for employee and org")
        return CreateNarmestelederbehovResult.AlreadyExists
    }

    private fun logDegraded(command: CreateNarmestelederbehovCommand, reason: StoredDegradedReason) {
        logger.logEvent(narmestelederbehovStoredDegraded, StoredDegradedDetails(command.source, reason))
    }

    private companion object {
        val logger = applicationLogger(CreateNarmestelederbehovUseCase::class.java)
    }
}

private data class ResolvedMainOrganization(val status: BehovStatus, val number: String) {
    companion object {
        private const val UNKNOWN_MAIN_ORGANIZATION = "UNKNOWN"

        fun found(number: String) = ResolvedMainOrganization(BehovStatus.BEHOV_CREATED, number)

        fun missing(status: BehovStatus) = ResolvedMainOrganization(status, UNKNOWN_MAIN_ORGANIZATION)
    }
}

private typealias CreateStep<T> = Step<T, CreateNarmestelederbehovResult>
