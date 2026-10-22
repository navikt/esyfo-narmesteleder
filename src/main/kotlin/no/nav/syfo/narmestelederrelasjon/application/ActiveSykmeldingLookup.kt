package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface ActiveSykmeldingLookup {
    suspend fun findActiveSykmelding(
        personIdents: List<PersonalIdentificationNumber>,
        organizationNumber: OrganizationNumber,
        operation: ActiveSykmeldingOperation,
    ): ActiveSykmeldingResult
}

enum class ActiveSykmeldingOperation(
    val metricValue: String,
) {
    ESTABLISH_RELATION("establish_relation"),
    GET_RELATION("get_relation"),
}

sealed interface ActiveSykmeldingResult {
    data object Found : ActiveSykmeldingResult
    data object NotFound : ActiveSykmeldingResult
    data class Unavailable(val failure: UpstreamFailure) : ActiveSykmeldingResult
}
