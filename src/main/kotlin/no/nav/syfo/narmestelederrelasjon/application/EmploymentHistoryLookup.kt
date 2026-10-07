package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.Employment

fun interface EmploymentHistoryLookup {
    suspend fun findEmploymentHistory(personIdent: PersonIdent): EmploymentHistoryResult
}

sealed interface EmploymentHistoryResult {
    data class Found(val employments: List<Employment>) : EmploymentHistoryResult

    data class Failed(val reason: EmploymentHistoryFailureReason) : EmploymentHistoryResult
}

enum class EmploymentHistoryFailureReason {
    PERSON_NOT_FOUND,
    UNAVAILABLE,
}
