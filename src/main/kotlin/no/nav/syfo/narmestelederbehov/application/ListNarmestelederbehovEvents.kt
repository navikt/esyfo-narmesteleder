package no.nav.syfo.narmestelederbehov.application

import no.nav.esyfo.observability.Event
import org.slf4j.event.Level

internal val listNarmestelederbehovRejected = Event<ListNarmestelederbehovResult>(
    name = "narmestelederbehov_list_rejected",
    level = Level.WARN,
    message = "Narmestelederbehov list rejected",
    operation = "list_narmestelederbehov",
    fields = mapOf(
        "outcome_code" to {
            when (it) {
                is ListNarmestelederbehovResult.Listed -> error("A listed behov cannot be rejected")
                is ListNarmestelederbehovResult.AccessDenied -> "ACCESS_DENIED"
                ListNarmestelederbehovResult.PersonNotFound -> "PERSON_NOT_FOUND"
            }
        },
        "denial_reason" to { (it as? ListNarmestelederbehovResult.AccessDenied)?.reason?.name },
    ),
)
