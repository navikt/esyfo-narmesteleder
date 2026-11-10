package no.nav.syfo.organisasjonstilgang

import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnAccessSettings

data class OrganisasjonstilgangSettings(
    val altinnAuthorization: AltinnAuthorizationSettings,
    val altinnTilganger: AltinnAccessSettings,
)
