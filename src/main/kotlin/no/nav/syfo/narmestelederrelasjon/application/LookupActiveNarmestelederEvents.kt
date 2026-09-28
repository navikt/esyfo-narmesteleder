package no.nav.syfo.narmestelederrelasjon.application

import no.nav.esyfo.observability.Event
import org.slf4j.event.Level

internal val multipleActiveRelations = Event<Int>(
    name = "multiple_active_relations",
    level = Level.ERROR,
    message = "Multiple active nearest leader relations were found; selecting the first",
    fields = mapOf("record_count" to { it }),
)
