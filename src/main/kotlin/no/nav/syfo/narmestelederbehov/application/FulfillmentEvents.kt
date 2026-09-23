package no.nav.syfo.narmestelederbehov.application

import no.nav.esyfo.observability.Event
import org.slf4j.event.Level

internal val fulfillmentCompleted = Event<FulfillNarmestelederbehovResult.Fulfilled>(
    name = "narmestelederbehov_fulfillment_completed",
    level = Level.INFO,
    message = "Narmestelederbehov fulfillment completed",
    operation = "fulfill_narmestelederbehov",
    fields = mapOf(
        "relation_source" to { it.relationSource.name },
        "dialogporten_completion" to {
            when (it.dialogCompletion) {
                DialogportenCompletionAttempt.Completed -> "COMPLETED"
                DialogportenCompletionAttempt.Failed -> "FAILED"
                DialogportenCompletionAttempt.NotApplicable -> "NOT_APPLICABLE"
            }
        },
    ),
)

internal val fulfillmentRejected = Event<FulfillNarmestelederbehovResult>(
    name = "narmestelederbehov_fulfillment_rejected",
    level = Level.WARN,
    message = "Narmestelederbehov fulfillment rejected",
    operation = "fulfill_narmestelederbehov",
    fields = mapOf(
        "outcome_code" to {
            when (it) {
                is FulfillNarmestelederbehovResult.Fulfilled -> error("A fulfilled behov cannot be rejected")
                is FulfillNarmestelederbehovResult.InvalidManagerContactDetails -> "INVALID_MANAGER_CONTACT_DETAILS"
                FulfillNarmestelederbehovResult.NotFound -> "NOT_FOUND"
                is FulfillNarmestelederbehovResult.AccessDenied -> "ACCESS_DENIED"
                is FulfillNarmestelederbehovResult.NoActiveSykmelding -> "NO_ACTIVE_SYKMELDING"
                is FulfillNarmestelederbehovResult.NoEmployment -> "NO_EMPLOYMENT"
                FulfillNarmestelederbehovResult.PersonNotFound -> "PERSON_NOT_FOUND"
                is FulfillNarmestelederbehovResult.ManagerNameMismatch -> "MANAGER_NAME_MISMATCH"
            }
        },
        "validation_issues" to {
            (it as? FulfillNarmestelederbehovResult.InvalidManagerContactDetails)?.issues?.take(20)?.map { issue ->
                mapOf("field" to issue.field.name, "reason" to issue.reason.name)
            }
        },
        "issue_count" to { (it as? FulfillNarmestelederbehovResult.InvalidManagerContactDetails)?.issues?.size },
    ),
)
