package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.esyfo.observability.Event
import no.nav.syfo.logging.applicationLogger
import no.nav.syfo.narmestelederbehov.application.DialogportenCompletionAttempt
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovOutcome
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovOutcomeLogger
import org.slf4j.event.Level

class FulfillNarmestelederbehovStructuredOutcomeLogger : FulfillNarmestelederbehovOutcomeLogger {
    override fun log(outcome: FulfillNarmestelederbehovOutcome) {
        when (outcome) {
            is FulfillNarmestelederbehovOutcome.Fulfilled -> logger.event(
                fulfillmentCompleted,
                FulfilledContext(
                    relationSource = outcome.relationSource.name,
                    dialogCompletion = outcome.dialogCompletion.logValue,
                ),
            )

            FulfillNarmestelederbehovOutcome.InvalidManagerContactDetails ->
                logger.event(fulfillmentRejected, RejectedContext("INVALID_MANAGER_CONTACT_DETAILS"))
            FulfillNarmestelederbehovOutcome.NotFound -> logger.event(fulfillmentRejected, RejectedContext("NOT_FOUND"))
            FulfillNarmestelederbehovOutcome.AccessDenied -> logger.event(fulfillmentRejected, RejectedContext("ACCESS_DENIED"))
            FulfillNarmestelederbehovOutcome.NoActiveSykmelding ->
                logger.event(fulfillmentRejected, RejectedContext("NO_ACTIVE_SYKMELDING"))
            FulfillNarmestelederbehovOutcome.NoEmployment -> logger.event(fulfillmentRejected, RejectedContext("NO_EMPLOYMENT"))
            FulfillNarmestelederbehovOutcome.PersonNotFound -> logger.event(fulfillmentRejected, RejectedContext("PERSON_NOT_FOUND"))
            FulfillNarmestelederbehovOutcome.ManagerNameMismatch ->
                logger.event(fulfillmentRejected, RejectedContext("MANAGER_NAME_MISMATCH"))
        }
    }

    private data class FulfilledContext(
        val relationSource: String,
        val dialogCompletion: String,
    )

    private data class RejectedContext(
        val outcomeCode: String,
    )

    private companion object {
        val fulfillmentCompleted = Event<FulfilledContext>(
            name = "narmestelederbehov_fulfillment_completed",
            level = Level.INFO,
            message = "Narmestelederbehov fulfillment completed",
            operation = "fulfill_narmestelederbehov",
            fields = mapOf(
                "relation_source" to { it.relationSource },
                "dialogporten_completion" to { it.dialogCompletion },
            ),
        )
        val fulfillmentRejected = Event<RejectedContext>(
            name = "narmestelederbehov_fulfillment_rejected",
            level = Level.WARN,
            message = "Narmestelederbehov fulfillment rejected",
            operation = "fulfill_narmestelederbehov",
            fields = mapOf(
                "outcome_code" to { it.outcomeCode },
            ),
        )
        val logger = applicationLogger(FulfillNarmestelederbehovStructuredOutcomeLogger::class.java)
    }
}

private val DialogportenCompletionAttempt.logValue: String
    get() = when (this) {
        DialogportenCompletionAttempt.Completed -> "COMPLETED"
        DialogportenCompletionAttempt.Failed -> "FAILED"
    }
