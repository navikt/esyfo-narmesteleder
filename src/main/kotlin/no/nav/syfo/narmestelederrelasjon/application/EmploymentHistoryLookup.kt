package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.Employment
import no.nav.syfo.platform.upstream.UpstreamFailure

fun interface EmploymentHistoryLookup {
    suspend fun findEmploymentHistory(personIdent: PersonIdent): EmploymentHistoryResult
}

sealed interface EmploymentHistoryResult {
    data class Found(val employments: List<Employment>) : EmploymentHistoryResult

    data class Unavailable(val failure: UpstreamFailure) : EmploymentHistoryResult
}
