package no.nav.syfo.narmestelederrelasjon

data class NarmestelederrelasjonSettings(
    val employmentCheckEnabled: Boolean,
    val employmentCheckObserveSourceEnabled: Boolean,
    val employmentCheckRevokeEnabled: Boolean,
    val lineManagerLookupPreAuthorizedApps: Set<String>,
) {
    init {
        require(!(employmentCheckRevokeEnabled && employmentCheckObserveSourceEnabled)) {
            "EMPLOYMENT_CHECK_REVOKE_ENABLED and EMPLOYMENT_CHECK_OBSERVE_SOURCE_ENABLED cannot both be enabled"
        }
    }
}
