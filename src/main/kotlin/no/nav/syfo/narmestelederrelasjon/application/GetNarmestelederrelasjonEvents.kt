package no.nav.syfo.narmestelederrelasjon.application

import no.nav.esyfo.observability.Event
import org.slf4j.event.Level

internal val narmestelederrelasjonNotFound = Event<GetNarmestelederrelasjonResult.NotFound>(
    name = "narmestelederrelasjon_not_found",
    level = Level.WARN,
    message = "Narmestelederrelasjon was not returned",
    operation = "get_narmestelederrelasjon",
    fields = mapOf(
        "outcome_code" to { it.reason.name },
        "denial_reason" to { it.denialReason?.name },
    ),
)
