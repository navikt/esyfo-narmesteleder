package no.nav.syfo.narmestelederbehov.application

import no.nav.esyfo.observability.Event
import org.slf4j.event.Level

internal val getNarmestelederbehovRejected = Event<GetNarmestelederbehovResult>(
    name = "narmestelederbehov_get_rejected",
    level = Level.WARN,
    message = "Narmestelederbehov lookup rejected",
    operation = "get_narmestelederbehov",
    fields = mapOf(
        "outcome_code" to {
            when (it) {
                is GetNarmestelederbehovResult.Found -> error("A found behov cannot be rejected")
                GetNarmestelederbehovResult.NotFound -> "NOT_FOUND"
                is GetNarmestelederbehovResult.AccessDenied -> "ACCESS_DENIED"
                GetNarmestelederbehovResult.PersonNotFound -> "PERSON_NOT_FOUND"
            }
        },
        "denial_reason" to { (it as? GetNarmestelederbehovResult.AccessDenied)?.reason?.name },
    ),
)
