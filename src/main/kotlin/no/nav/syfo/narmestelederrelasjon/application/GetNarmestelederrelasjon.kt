package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonResult.NotFound
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonResult.NotFoundReason
import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.util.UUID

class GetNarmestelederrelasjon(
    private val repository: NarmestelederrelasjonRepository,
    private val organizationAccess: OrganizationAccess,
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
            OrganizationAccessResult.Granted -> Unit

            is OrganizationAccessResult.Denied ->
                return NotFound(NotFoundReason.ACCESS_DENIED, denialReason = access.reason).log()
        }

        if (!activeSykmeldingLookup.hasActiveSykmelding(lookup.employeeIdent, lookup.organizationNumber)) {
            return NotFound(NotFoundReason.NO_ACTIVE_SYKMELDING).log()
        }

        val firstName = lookup.employeeFirstName?.takeIf(String::isNotBlank)
        val middleName = lookup.employeeMiddleName?.takeIf(String::isNotBlank)
        val lastName = lookup.employeeLastName?.takeIf(String::isNotBlank)
        val organizationName = organization.findName(lookup.organizationNumber)
            ?: return GetNarmestelederrelasjonResult.Unavailable

        val relation = Narmestelederrelasjon(
            id = lookup.id,
            orgNumber = lookup.organizationNumber,
            employee = RelationPerson(
                personIdent = lookup.employeeIdent,
                firstName = firstName,
                middleName = middleName,
                lastName = lastName,
            ),
        )

        return GetNarmestelederrelasjonResult.Found(
            relation = relation,
            organizationName = organizationName,
        )
    }

    private fun NotFound.log(): NotFound = also { logger.event(narmestelederrelasjonNotFound, it) }

    private companion object {
        val logger = applicationLogger(GetNarmestelederrelasjon::class.java)
    }
}
