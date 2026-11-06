package no.nav.syfo.bootstrap

import io.ktor.server.config.ApplicationConfig
import no.nav.syfo.organisasjonstilgang.OrganisasjonstilgangSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnAccessSettings
import no.nav.syfo.platform.config.string

internal fun ApplicationConfig.organisasjonstilgangSettings() = OrganisasjonstilgangSettings(
    altinnAuthorization = AltinnAuthorizationSettings(subscriptionKey = string("altinn.authorization.subscriptionKey")),
    altinnTilganger = AltinnAccessSettings(baseUrl = string("altinn.tilganger.baseUrl")),
)
