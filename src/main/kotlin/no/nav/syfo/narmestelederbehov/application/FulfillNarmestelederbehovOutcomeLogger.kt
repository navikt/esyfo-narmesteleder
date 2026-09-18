package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederrelasjon.application.RelationSource

fun interface FulfillNarmestelederbehovOutcomeLogger {
    fun log(outcome: FulfillNarmestelederbehovOutcome)
}

sealed interface FulfillNarmestelederbehovOutcome {
    data class Fulfilled(
        val relationSource: RelationSource,
        val dialogCompletion: DialogportenCompletionAttempt,
    ) : FulfillNarmestelederbehovOutcome

    data object InvalidManagerContactDetails : FulfillNarmestelederbehovOutcome
    data object NotFound : FulfillNarmestelederbehovOutcome
    data object AccessDenied : FulfillNarmestelederbehovOutcome
    data object NoActiveSykmelding : FulfillNarmestelederbehovOutcome
    data object NoEmployment : FulfillNarmestelederbehovOutcome
    data object PersonNotFound : FulfillNarmestelederbehovOutcome
    data object ManagerNameMismatch : FulfillNarmestelederbehovOutcome
}
