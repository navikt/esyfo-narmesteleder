package no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger

import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganization
import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganizationsLookup
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsResult
import no.nav.syfo.organisasjonstilgang.application.OPPGI_NARMESTELEDER_RESOURCE
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamResult

class AltinnTilgangerService(
    val altinnTilgangerClient: AltinnTilgangerClient,
) : AccessibleOrganizationsLookup {
    suspend fun getAltinnTilgangForOrgnr(
        userPrincipal: UserPrincipal,
        orgnummer: String,
    ): UpstreamResult<AltinnTilgang?> = when (val result = altinnTilgangerClient.fetchAltinnTilganger(userPrincipal)) {
        is UpstreamResult.Success -> UpstreamResult.Success(result.value.hierarki.findByOrgnr(orgnummer))
        is UpstreamResult.Failure -> result
    }

    override suspend fun find(subject: OrganizationAccessSubject.PersonnelManager): ListAccessibleOrganizationsResult {
        val userPrincipal = UserPrincipal(subject.personIdent.value, subject.accessToken.value())
        return when (val result = altinnTilgangerClient.fetchAltinnTilganger(userPrincipal)) {
            is UpstreamResult.Success -> ListAccessibleOrganizationsResult.Listed(result.value.hierarki.filterToOrganizations())
            is UpstreamResult.Failure -> ListAccessibleOrganizationsResult.Unavailable(result.failure)
        }
    }

    private fun List<AltinnTilgang>.filterToOrganizations(): List<AccessibleOrganization> = mapNotNull { it.filterAccess() }

    private fun AltinnTilgang.filterAccess(): AccessibleOrganization? {
        val filteredSubOrganizations = underenheter.filterToOrganizations()
        val hasAccess = hasNarmestelederTilgang()

        return if (hasAccess || filteredSubOrganizations.isNotEmpty()) {
            AccessibleOrganization(
                organizationNumber = orgnr,
                name = navn,
                subOrganizations = filteredSubOrganizations,
            )
        } else {
            null
        }
    }

    private fun AltinnTilgang.hasNarmestelederTilgang(): Boolean = altinn3Tilganger.contains(OPPGI_NARMESTELEDER_RESOURCE)

    private fun List<AltinnTilgang>.findByOrgnr(targetOrgnr: String): AltinnTilgang? {
        for (tilgang in this) {
            if (tilgang.orgnr == targetOrgnr) {
                return tilgang
            }
            tilgang.underenheter.findByOrgnr(targetOrgnr)?.let { return it }
        }
        return null
    }
}
