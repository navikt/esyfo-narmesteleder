package no.nav.syfo.narmestelederrelasjon.application

import no.nav.esyfo.observability.Event
import no.nav.syfo.logging.applicationEvent
import org.slf4j.event.Level
import java.util.UUID

internal val employmentCheckFailed = applicationEvent<UUID>(
    name = "employment_check_failed",
    level = Level.WARN,
    message = "Employment history lookup failed; the relation will be checked again",
    operation = "check_narmestelederrelasjon_employment",
    upstream = "aareg",
    fields = mapOf("narmesteleder_id" to { it.toString() }),
)

internal val employmentCheckSeedCompleted = Event<Int>(
    name = "employment_check_seed_completed",
    level = Level.INFO,
    message = "Employment check seeding completed",
    operation = "seed_narmestelederrelasjon_employment_checks",
    fields = mapOf("seeded_count" to { it }),
)
