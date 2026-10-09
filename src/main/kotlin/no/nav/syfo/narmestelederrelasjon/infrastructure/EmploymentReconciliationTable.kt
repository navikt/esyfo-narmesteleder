package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.narmestelederrelasjon.application.EmploymentCheckOutcome
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

internal enum class EmploymentCheckStatus { READY, CLAIMED }

/**
 * Work queue for checking relations against Aareg (V31). One row per relation id.
 *
 * - `next_check_at` has two meanings: when `status = READY` it is when the next check is due;
 *   when `status = CLAIMED` it is when the lease expires and another pod may reclaim the row.
 * - `claim_token` is set only while CLAIMED and fences writes from a pod that lost its claim.
 * - No foreign key to `narmeste_leder`: Leesah events may arrive in any order, and rows are kept
 *   after a relation ends for the shadow comparison.
 * - `shadow_would_revoke_at` records the first time shadow mode decided the relation would be
 *   revoked; nothing was published.
 * - `source_revocation_observed_at` records when team sykmelding's revocation
 *   (Leesah `DEAKTIVERT_ARBEIDSFORHOLD`) was observed.
 * - Both timestamps are only for the shadow comparison and will be removed after cutover (#647).
 */
internal object EmploymentReconciliationTable : Table("narmestelederrelasjon_employment_check") {
    val narmestelederId = javaUUID("narmeste_leder_id")
    val status = customEnumeration(
        name = "status",
        sql = "text",
        fromDb = { EmploymentCheckStatus.valueOf(it as String) },
        toDb = { it.name },
    )
    val nextCheckAt = timestampWithTimeZone("next_check_at")
    val claimToken = javaUUID("claim_token").nullable()
    val lastCheckedAt = timestampWithTimeZone("last_checked_at").nullable()
    val lastOutcome = customEnumeration(
        name = "last_outcome",
        sql = "text",
        fromDb = { EmploymentCheckOutcome.valueOf(it as String) },
        toDb = { it.name },
    ).nullable()
    val shadowWouldRevokeAt = timestampWithTimeZone("shadow_would_revoke_at").nullable()
    val sourceRevocationObservedAt = timestampWithTimeZone("source_revocation_observed_at").nullable()
    val created = timestampWithTimeZone("created").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(narmestelederId, name = "nlrel_employment_check_pkey")

    init {
        index("nlrel_employment_check_next_check_at_idx", false, nextCheckAt)
        check("nlrel_employment_check_status_check") { status inList EmploymentCheckStatus.entries }
        check("nlrel_employment_check_outcome_check") {
            lastOutcome.isNull() or (lastOutcome inList EmploymentCheckOutcome.entries)
        }
        check("nlrel_employment_check_claim_token_check") {
            ((status eq EmploymentCheckStatus.CLAIMED) and claimToken.isNotNull()) or
                ((status eq EmploymentCheckStatus.READY) and claimToken.isNull())
        }
    }
}
