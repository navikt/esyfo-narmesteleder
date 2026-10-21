package no.nav.syfo.narmestelederrelasjon

data class NarmestelederrelasjonSettings(
    val employmentCheckObserveSourceEnabled: Boolean,
    val employmentCheckRevokeEnabled: Boolean,
    val lineManagerLookupPreAuthorizedApps: Set<String>,
)
