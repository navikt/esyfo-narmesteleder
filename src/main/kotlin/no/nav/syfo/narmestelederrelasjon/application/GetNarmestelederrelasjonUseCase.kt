package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonResult.NotFound
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonResult.NotFoundReason
import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.observability.applicationLogger
import java.util.UUID

class GetNarmestelederrelasjonUseCase(
    private val repository: NarmestelederrelasjonRepository,
    private val organizationAccess: OrganizationAccess,
    private val personIdentifiersLookup: PersonIdentifiersLookup,
    private val activeSykmeldingLookup: ActiveSykmeldingLookup,
    private val organization: NarmestelederrelasjonOrganization,
) {
    suspend fun execute(
        id: UUID,
        accessSubject: OrganizationAccessSubject,
    ): GetNarmestelederrelasjonResult {
        val lookup = repository.findById(id)
            ?: return NotFound(NotFoundReason.RELATION_NOT_FOUND).log()
        if (!lookup.isActive) {
            return NotFound(NotFoundReason.RELATION_INACTIVE).log()
        }

        when (val access = organizationAccess.evaluate(accessSubject, lookup.organizationNumber)) {
            is OrganizationAccessResult.Granted -> Unit

            is OrganizationAccessResult.Unavailable -> return GetNarmestelederrelasjonResult.UpstreamUnavailable(access.failure)

            is OrganizationAccessResult.Denied ->
                return NotFound(NotFoundReason.ACCESS_DENIED, denialReason = access.reason).log()
        }

        val identifiers = when (
            val result = personIdentifiersLookup.find(
                lookup.employeeIdent,
                PersonIdentifiersLookupOperation.GET_RELATION,
            )
        ) {
            is PersonIdentifiersLookupResult.Found -> result.identifiers
            PersonIdentifiersLookupResult.NotFound -> return NotFound(NotFoundReason.PERSON_NOT_FOUND).log()
            is PersonIdentifiersLookupResult.Unavailable -> return GetNarmestelederrelasjonResult.UpstreamUnavailable(result.failure)
        }

        when (
            val activeSykmelding = activeSykmeldingLookup.findActiveSykmelding(
                personIdents = identifiers.all,
                organizationNumber = lookup.organizationNumber,
                operation = ActiveSykmeldingOperation.GET_RELATION,
            )
        ) {
            ActiveSykmeldingResult.Found -> Unit
            ActiveSykmeldingResult.NotFound -> return NotFound(NotFoundReason.NO_ACTIVE_SYKMELDING).log()
            is ActiveSykmeldingResult.Unavailable -> return GetNarmestelederrelasjonResult.UpstreamUnavailable(activeSykmelding.failure)
        }

        val organizationName = when (val result = organization.findName(lookup.organizationNumber)) {
            is OrganizationNameResult.Found -> result.name

            OrganizationNameResult.Missing -> {
                logger.event(narmestelederrelasjonOrganizationNameMissing)
                null
            }

            is OrganizationNameResult.Unavailable -> return GetNarmestelederrelasjonResult.UpstreamUnavailable(result.failure)
        }

        return GetNarmestelederrelasjonResult.Found(
            relation = lookup.toRelation(),
            organizationName = organizationName,
        )
    }

    private fun NotFound.log(): NotFound = also { logger.event(narmestelederrelasjonNotFound, it) }

    private companion object {
        val logger = applicationLogger(GetNarmestelederrelasjonUseCase::class.java)
    }
}

private fun NarmestelederrelasjonLookup.toRelation() = Narmestelederrelasjon(
    id = id,
    orgNumber = organizationNumber,
    employee = RelationPerson(
        personIdent = employeeIdent,
        firstName = employeeFirstName.nullIfBlank(),
        middleName = employeeMiddleName.nullIfBlank(),
        lastName = employeeLastName.nullIfBlank(),
    ),
)

private fun String?.nullIfBlank(): String? = this?.takeIf(String::isNotBlank)
