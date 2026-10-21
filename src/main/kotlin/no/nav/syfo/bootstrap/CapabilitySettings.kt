package no.nav.syfo.bootstrap

import no.nav.syfo.narmestelederbehov.NarmestelederbehovSettings
import no.nav.syfo.narmestelederrelasjon.NarmestelederrelasjonSettings
import kotlin.time.Duration

internal fun Environment.narmestelederbehovSettings() = NarmestelederbehovSettings(
    persistLeesahNlBehov = otherProperties.persistLeesahNlBehov,
    publicIngressUrl = otherProperties.publicIngressUrl,
    frontendBaseUrl = otherProperties.frontendBaseUrl,
    dialogUpdatePollingInterval = Duration.parse(otherProperties.updateDialogportenTaskProperties.pollingDelay),
)

internal fun Environment.narmestelederrelasjonSettings() = NarmestelederrelasjonSettings(
    employmentCheckObserveSourceEnabled = otherProperties.employmentCheckObserveSourceEnabled,
    employmentCheckRevokeEnabled = otherProperties.employmentCheckRevokeEnabled,
    lineManagerLookupPreAuthorizedApps = texas.azurePreAuthorizedApps,
)
