package no.nav.syfo.narmestelederbehov.application

interface NarmestelederbehovCreationMetrics {
    fun recordSkippedNoActiveSykmelding()

    fun recordSkippedAlreadyExists()

    fun recordStoredWithoutMainOrganization()

    fun recordStoredWithoutEmployment()
}
