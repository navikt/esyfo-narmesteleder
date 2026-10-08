package no.nav.syfo.narmestelederrelasjon.infrastructure

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

/**
 * Work queue for checking relations against Aareg (V31). One row per relation id.
 *
 * - `neste_kontroll` has two meanings: when `status = KLAR` it is when the next check is due;
 *   when `status = CLAIMED` it is when the lease expires and another pod may reclaim the row.
 * - `claim_token` is set only while CLAIMED and fences writes from a pod that lost its claim.
 * - No foreign key to `narmeste_leder`: Leesah events may arrive in any order, and rows are kept
 *   after a relation ends for the shadow comparison.
 * - `skygge_ville_brutt` and `observert_brudd_fra_kilde` are used by the shadow comparison (#473).
 */
internal object EmploymentReconciliationTable : Table("narmestelederrelasjon_arbeidsforhold_kontroll") {
    val narmestelederId = javaUUID("narmeste_leder_id")
    val status = text("status")
    val nesteKontroll = timestampWithTimeZone("neste_kontroll")
    val claimToken = javaUUID("claim_token").nullable()
    val sistKontrollert = timestampWithTimeZone("sist_kontrollert").nullable()
    val sistUtfall = text("sist_utfall").nullable()
    val skyggeVilleBrutt = timestampWithTimeZone("skygge_ville_brutt").nullable()
    val observertBruddFraKilde = timestampWithTimeZone("observert_brudd_fra_kilde").nullable()
    val opprettet = timestampWithTimeZone("opprettet").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(narmestelederId, name = "nlrel_arbeidsforhold_kontroll_pkey")

    init {
        index("nlrel_arbeidsforhold_kontroll_neste_kontroll_idx", false, nesteKontroll)
        check("nlrel_arbeidsforhold_kontroll_status_check") { status inList listOf("KLAR", "CLAIMED") }
        check("nlrel_arbeidsforhold_kontroll_utfall_check") {
            sistUtfall.isNull() or (sistUtfall inList listOf("GYLDIG", "VILLE_BRUTT", "BRUTT", "FEILET"))
        }
        check("nlrel_arbeidsforhold_kontroll_claim_token_check") {
            ((status eq "CLAIMED") and claimToken.isNotNull()) or ((status eq "KLAR") and claimToken.isNull())
        }
    }
}
