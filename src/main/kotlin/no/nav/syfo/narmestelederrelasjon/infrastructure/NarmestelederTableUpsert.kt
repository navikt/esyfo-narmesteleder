package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonUpsert
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.ZoneOffset

/**
 * Upserts a row in [NarmestelederTable] from a validated relation.
 *
 * **On INSERT** — all data fields from the relation are written.
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
 * - `sykmeldtFnr`, `narmestelederFnr` — domain-immutable identifiers
 * - `brukerNavn`, `narmestelederNavn` — owned by PDL enrichment
 * - `created` — insert-only timestamp
 * - `updated` — managed by a DB trigger
 */
internal fun NarmestelederTable.upsertRelasjon(relasjon: NarmestelederrelasjonUpsert) {
    upsert(
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
        it[NarmestelederTable.narmestelederId] = relasjon.narmestelederId
        it[NarmestelederTable.orgnummer] = relasjon.orgnummer.value
        it[NarmestelederTable.sykmeldtFnr] = relasjon.sykmeldtFnr.value
        it[NarmestelederTable.narmestelederFnr] = relasjon.narmestelederFnr.value
        it[NarmestelederTable.narmestelederTelefonnummer] = relasjon.narmestelederTelefonnummer
        it[NarmestelederTable.narmestelederEpost] = relasjon.narmestelederEpost
        it[NarmestelederTable.arbeidsgiverForskutterer] = relasjon.arbeidsgiverForskutterer
        it[NarmestelederTable.aktivFom] = relasjon.aktivFom.atStartOfDay().atOffset(ZoneOffset.UTC)
        it[NarmestelederTable.aktivTom] = relasjon.aktivTom?.atStartOfDay()?.atOffset(ZoneOffset.UTC)
    }
}
