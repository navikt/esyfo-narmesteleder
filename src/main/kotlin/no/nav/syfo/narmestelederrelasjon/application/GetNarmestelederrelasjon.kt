package no.nav.syfo.narmestelederrelasjon.application

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
        val lookup = repository.findById(id) ?: return GetNarmestelederrelasjonResult.NotFound

        when (organizationAccess.evaluate(accessSubject, lookup.organizationNumber)) {
            OrganizationAccessResult.Granted -> Unit
            is OrganizationAccessResult.Denied -> return GetNarmestelederrelasjonResult.NotFound
        }

        if (!activeSykmeldingLookup.hasActiveSykmelding(lookup.employeeIdent, lookup.organizationNumber)) {
            return GetNarmestelederrelasjonResult.NotFound
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
}
