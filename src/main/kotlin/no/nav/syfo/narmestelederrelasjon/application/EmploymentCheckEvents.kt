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

internal val employmentCheckSourceObservationFailed = applicationEvent<UUID>(
    name = "employment_check_source_observation_failed",
    level = Level.WARN,
    message = "Employment revocation observation failed after register commit; ingestion continues",
    operation = "record_source_employment_revocation",
    fields = mapOf("narmesteleder_id" to { it.toString() }),
)

internal val employmentCheckComparisonMismatch = Event<UUID>(
    name = "employment_check_comparison_mismatch",
    level = Level.WARN,
    message = "Employment check kept a relation revoked by the source",
    operation = "check_narmestelederrelasjon_employment",
    fields = mapOf("narmesteleder_id" to { it.toString() }),
)

internal val employmentCheckBatchCompleted = Event<CheckBatchResult>(
    name = "employment_check_batch_completed",
    level = Level.INFO,
    message = "Employment check batch completed",
    operation = "check_narmestelederrelasjon_employment",
    fields = mapOf(
        "valid" to { it.valid },
        "would_revoke" to { it.wouldRevoke },
        "failed" to { it.failed },
        "claim_lost" to { it.claimLost },
        "timeout" to { it.timeout },
    ),
)

internal data class EmploymentCheckSeedSummary(val seededCount: Long, val rounds: Int)

internal val employmentCheckSeedCompleted = Event<EmploymentCheckSeedSummary>(
    name = "employment_check_seed_completed",
    level = Level.INFO,
    message = "Employment check seeding completed",
    operation = "seed_narmestelederrelasjon_employment_checks",
    fields = mapOf(
        "seeded_count" to { it.seededCount },
        "rounds" to { it.rounds },
    ),
)
