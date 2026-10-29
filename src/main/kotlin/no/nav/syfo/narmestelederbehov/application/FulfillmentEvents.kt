package no.nav.syfo.narmestelederbehov.application

import no.nav.esyfo.observability.Event
import no.nav.syfo.platform.observability.applicationEvent
import org.slf4j.event.Level

internal val fulfillmentCompleted = Event<FulfilledNarmestelederbehov>(
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

internal val fulfillmentRejected = Event<FulfillNarmestelederbehovError>(
    name = "narmestelederbehov_fulfillment_rejected",
    level = Level.WARN,
    message = "Narmestelederbehov fulfillment rejected",
    operation = "fulfill_narmestelederbehov",
    fields = mapOf(
        "outcome_code" to {
            when (it) {
                is FulfillNarmestelederbehovError.InvalidManagerContactDetails -> "INVALID_MANAGER_CONTACT_DETAILS"
                FulfillNarmestelederbehovError.NotFound -> "NOT_FOUND"
                FulfillNarmestelederbehovError.BehovMissingAfterPublication -> "BEHOV_MISSING_AFTER_PUBLICATION"
                is FulfillNarmestelederbehovError.AccessDenied -> "ACCESS_DENIED"
                is FulfillNarmestelederbehovError.NoActiveSykmelding -> "NO_ACTIVE_SYKMELDING"
                is FulfillNarmestelederbehovError.NoEmployment -> "NO_EMPLOYMENT"
                is FulfillNarmestelederbehovError.UpstreamUnavailable -> "UPSTREAM_UNAVAILABLE"
                FulfillNarmestelederbehovError.PersonNotFound -> "PERSON_NOT_FOUND"
                FulfillNarmestelederbehovError.ManagerNameMismatch -> "MANAGER_NAME_MISMATCH"
            }
        },
        "validation_issues" to {
            (it as? FulfillNarmestelederbehovError.InvalidManagerContactDetails)?.issues?.take(20)?.map { issue ->
                mapOf("field" to issue.field.name, "reason" to issue.reason.name)
            }
        },
        "issue_count" to { (it as? FulfillNarmestelederbehovError.InvalidManagerContactDetails)?.issues?.size },
    ),
)

internal val dialogStatusPersistenceFailed = applicationEvent<String>(
    name = "narmestelederbehov_dialog_status_persistence_failed",
    level = Level.WARN,
    message = "Dialogporten completed but behov status could not be persisted",
    operation = "fulfill_narmestelederbehov",
    fields = mapOf("behov_id" to { it }),
)

internal val dialogportenCompletionFailed = applicationEvent<String>(
    name = "narmestelederbehov_dialogporten_completion_failed",
    level = Level.WARN,
    message = "Dialogporten completion failed; pending behov remains retryable",
    operation = "fulfill_narmestelederbehov",
    upstream = "dialogporten",
    fields = mapOf("behov_id" to { it }),
)
