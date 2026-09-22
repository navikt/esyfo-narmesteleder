package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.application.auth.Principal
import java.util.UUID

class GetNarmestelederrelasjon(
    private val repository: NarmestelederrelasjonRepository,
    private val organizationAccess: NarmestelederrelasjonOrganizationAccess,
    private val organization: NarmestelederrelasjonOrganization,
) {
    suspend fun execute(
        id: UUID,
        principal: Principal,
    ): GetNarmestelederrelasjonResult {
        val relation = repository.findActiveById(id) ?: return GetNarmestelederrelasjonResult.NotFound

        if (!organizationAccess.hasAccess(principal, relation.orgNumber)) {
            return GetNarmestelederrelasjonResult.NotFound
        }

        val employeeName = relation.employee.name
            ?.takeIf { it.firstName.isNotBlank() && it.lastName.isNotBlank() }
            ?: return GetNarmestelederrelasjonResult.NotFound
        val organizationName = organization.findName(relation.orgNumber) ?: return GetNarmestelederrelasjonResult.NotFound

        return GetNarmestelederrelasjonResult.Found(
            relation = relation,
            employeeName = employeeName,
            organizationName = organizationName,
        )
    }
}
