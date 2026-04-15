package no.nav.syfo.narmesteleder.exposed

import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.ZoneOffset

internal class NarmestelederTableOps(private val transaction: Transaction) {

    /**
     * Upserts a row in [NarmestelederTable] from a [NarmestelederLeesahKafkaMessage].
     *
     * **On INSERT** — all data fields from the Kafka message are written.
     * `brukerNavn` and `narmestelederNavn` are set to `null` (PDL-owned, populated separately).
     * `created` and `updated` use their DB default expressions.
     *
     * **On CONFLICT** (`narmesteleder_id`) — only mutable operational fields are updated:
     * - `orgnummer`
     * - `narmestelederTelefonnummer`
     * - `narmestelederEpost`
     * - `arbeidsgiverForskutterer`
     * - `aktivFom`
     * - `aktivTom`
     *
     * The following fields are **preserved** (not updated on conflict):
     * - `brukerFnr`, `narmestelederFnr` — domain-immutable identifiers
     * - `brukerNavn`, `narmestelederNavn` — owned by PDL enrichment
     * - `created` — insert-only timestamp
     * - `updated` — managed by a DB trigger
     */
    fun upsertFromLeesahKafkaMessage(kafkaMessage: NarmestelederLeesahKafkaMessage) {
        NarmestelederTable.upsert(
            NarmestelederTable.narmestelederId,
            onUpdate = {
                it[NarmestelederTable.orgnummer] = insertValue(NarmestelederTable.orgnummer)
                it[NarmestelederTable.narmestelederTelefonnummer] =
                    insertValue(NarmestelederTable.narmestelederTelefonnummer)
                it[NarmestelederTable.narmestelederEpost] = insertValue(NarmestelederTable.narmestelederEpost)
                it[NarmestelederTable.arbeidsgiverForskutterer] =
                    insertValue(NarmestelederTable.arbeidsgiverForskutterer)
                it[NarmestelederTable.aktivFom] = insertValue(NarmestelederTable.aktivFom)
                it[NarmestelederTable.aktivTom] = insertValue(NarmestelederTable.aktivTom)
            },
        ) {
            it[NarmestelederTable.narmestelederId] = kafkaMessage.narmesteLederId
            it[NarmestelederTable.orgnummer] = kafkaMessage.orgnummer
            it[NarmestelederTable.brukerFnr] = kafkaMessage.fnr
            it[NarmestelederTable.narmestelederFnr] = kafkaMessage.narmesteLederFnr
            it[NarmestelederTable.narmestelederTelefonnummer] = kafkaMessage.narmesteLederTelefonnummer
            it[NarmestelederTable.narmestelederEpost] = kafkaMessage.narmesteLederEpost
            it[NarmestelederTable.arbeidsgiverForskutterer] = kafkaMessage.arbeidsgiverForskutterer
            it[NarmestelederTable.aktivFom] = kafkaMessage.aktivFom.atStartOfDay().atOffset(ZoneOffset.UTC)
            it[NarmestelederTable.aktivTom] = kafkaMessage.aktivTom?.atStartOfDay()?.atOffset(ZoneOffset.UTC)
        }
    }
}

/**
 * Provides convenient access to [NarmestelederTableOps] from within an Exposed `transaction` block.
 */
internal val Transaction.narmestelederTable: NarmestelederTableOps
    get() = NarmestelederTableOps(this)
