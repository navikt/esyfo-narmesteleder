package no.nav.syfo.narmesteleder.exposed

import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.ZoneOffset

/**
 * Upserts a row in [NarmestelederTable] from a [NarmestelederLeesahKafkaMessage].
 *
 * Must be called within an Exposed `transaction` block.
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
fun NarmestelederTable.upsertFromLeesahKafkaMessage(kafkaMessage: NarmestelederLeesahKafkaMessage) {
    NarmestelederTable.upsert(
        NarmestelederTable.narmestelederId,
        onUpdate = {
            it[orgnummer] = insertValue(orgnummer)
            it[narmestelederTelefonnummer] = insertValue(narmestelederTelefonnummer)
            it[narmestelederEpost] = insertValue(narmestelederEpost)
            it[arbeidsgiverForskutterer] = insertValue(arbeidsgiverForskutterer)
            it[aktivFom] = insertValue(aktivFom)
            it[aktivTom] = insertValue(aktivTom)
        },
    ) {
        it[narmestelederId] = kafkaMessage.narmesteLederId
        it[orgnummer] = kafkaMessage.orgnummer
        it[brukerFnr] = kafkaMessage.fnr
        it[brukerNavn] = null
        it[narmestelederNavn] = null
        it[narmestelederFnr] = kafkaMessage.narmesteLederFnr
        it[narmestelederTelefonnummer] = kafkaMessage.narmesteLederTelefonnummer
        it[narmestelederEpost] = kafkaMessage.narmesteLederEpost
        it[arbeidsgiverForskutterer] = kafkaMessage.arbeidsgiverForskutterer
        it[aktivFom] = kafkaMessage.aktivFom.atStartOfDay().atOffset(ZoneOffset.UTC)
        it[aktivTom] = kafkaMessage.aktivTom?.atStartOfDay()?.atOffset(ZoneOffset.UTC)
    }
}
