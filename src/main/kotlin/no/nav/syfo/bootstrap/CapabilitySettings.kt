package no.nav.syfo.bootstrap

import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings

internal fun Environment.narmestelederbehovSettings() = NarmestelederbehovSettings(
    persistLeesahNlBehov = otherProperties.persistLeesahNlBehov,
    publicIngressUrl = otherProperties.publicIngressUrl,
    frontendBaseUrl = otherProperties.frontendBaseUrl,
)

internal fun Environment.narmestelederrelasjonSettings() = NarmestelederrelasjonSettings(
    employmentCheckObserveSourceEnabled = otherProperties.employmentCheckObserveSourceEnabled,
    employmentCheckRevokeEnabled = otherProperties.employmentCheckRevokeEnabled,
    lineManagerLookupPreAuthorizedApps = texas.azurePreAuthorizedApps,
)
