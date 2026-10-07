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
