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

        val organizationName = organization.findName(lookup.organizationNumber)
            ?: return GetNarmestelederrelasjonResult.Unavailable

        return GetNarmestelederrelasjonResult.Found(
            relation = lookup.toRelation(),
            organizationName = organizationName,
        )
    }

    private fun NotFound.log(): NotFound = also { logger.event(narmestelederrelasjonNotFound, it) }

    private companion object {
        val logger = applicationLogger(GetNarmestelederrelasjon::class.java)
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
