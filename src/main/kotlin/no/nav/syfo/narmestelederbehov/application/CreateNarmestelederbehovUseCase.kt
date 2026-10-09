package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.logging.logEvent
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.platform.application.Step
import no.nav.syfo.platform.application.orStop

/**
 * Stores a new behov when the employee has an active sykmelding and no open behov in the organization.
 * A behov whose main organization cannot be resolved is still stored, with an error status and no dialog.
 */
class CreateNarmestelederbehovUseCase(
    private val settings: NarmestelederbehovCreationSettings,
    private val repository: NarmestelederbehovRepository,
    private val activeSykmelding: NarmestelederbehovActiveSykmeldingLookup,
    private val mainOrganization: EmployerMainOrganizationLookup,
    private val dialog: NarmestelederbehovDialogCreation,
    private val metrics: NarmestelederbehovCreationMetrics,
) : CreateNarmestelederbehov {
    override suspend fun execute(command: CreateNarmestelederbehovCommand): CreateNarmestelederbehovResult {
        if (!settings.persistenceEnabled) {
            logger.info("Skipping persistence of LinemanagerRequirement as configured.")
            return CreateNarmestelederbehovResult.Disabled
        }
        if (repository.findOpenFor(command.employee).isNotEmpty()) {
            return alreadyExists()
        }
        if (!command.sykmeldingKnownActive && !activeSykmelding.hasActiveSykmelding(command.employee)) {
            metrics.recordSkippedNoActiveSykmelding()
            logger.info(
                "Not inserting NarmestelederBehovEntity as there is no active sick leave for employee with" +
                    " narmestelederId ${command.revokedRelationId}"
            )
            return CreateNarmestelederbehovResult.NoActiveSykmelding
        }

        val (status, mainOrganizationNumber) = resolveMainOrganization(command).orStop { return it }
        val behov = NewNarmestelederbehov(
            employee = command.employee,
            mainOrganizationNumber = mainOrganizationNumber,
            manager = command.manager,
            reason = command.reason,
            status = status,
            revokedRelationId = command.revokedRelationId,
        )
        val id = when (val created = repository.create(behov)) {
            is CreateBehovResult.Created -> created.id
            CreateBehovResult.AlreadyExists -> return alreadyExists()
        }
        logger.info("Inserted NarmestelederBehovEntity with id: ${id.value}")
        if (status !in BehovStatus.errorStatusList()) {
            dialog.create(id, behov)
        }
        return CreateNarmestelederbehovResult.Created(id)
    }

    private suspend fun resolveMainOrganization(
        command: CreateNarmestelederbehovCommand,
    ): Step<Pair<BehovStatus, String>, CreateNarmestelederbehovResult> = when (val source = command.mainOrganization) {
        is MainOrganizationSource.FromSykmelding -> {
            val reported = source.mainOrganizationNumber
            if (reported == null) {
                metrics.recordStoredWithoutMainOrganization()
                logDegraded(command, StoredDegradedReason.SICK_LEAVE_MAIN_ORG_MISSING)
                Step.Continue(BehovStatus.HOVEDENHET_NOT_FOUND to UNKNOWN_MAIN_ORGANIZATION)
            } else {
                Step.Continue(BehovStatus.BEHOV_CREATED to reported)
            }
        }

        MainOrganizationSource.FromEmployment -> when (val found = mainOrganization.findMainOrganization(command.employee)) {
            is MainOrganizationResult.Found -> Step.Continue(BehovStatus.BEHOV_CREATED to found.mainOrganizationNumber)
            MainOrganizationResult.EmploymentMissing -> {
                metrics.recordStoredWithoutEmployment()
                logDegraded(command, StoredDegradedReason.EMPLOYMENT_MISSING)
                Step.Continue(BehovStatus.ARBEIDSFORHOLD_NOT_FOUND to UNKNOWN_MAIN_ORGANIZATION)
            }
            MainOrganizationResult.MainOrganizationMissing -> {
                logDegraded(command, StoredDegradedReason.EMPLOYMENT_MAIN_ORG_MISSING)
                Step.Continue(BehovStatus.HOVEDENHET_NOT_FOUND to UNKNOWN_MAIN_ORGANIZATION)
            }
            is MainOrganizationResult.Unavailable -> Step.Stop(CreateNarmestelederbehovResult.UpstreamUnavailable(found.failure))
        }
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
        const val UNKNOWN_MAIN_ORGANIZATION = "UNKNOWN"
        val logger = applicationLogger(CreateNarmestelederbehovUseCase::class.java)
    }
}
