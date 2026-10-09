package no.nav.syfo.narmestelederrelasjon.application

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A relation as reported on the Leesah topic, before validation. Identifiers are raw strings because
 * invalid records must be logged and skipped, not rejected while parsing.
 */
data class LeesahNarmestelederrelasjon(
    val narmestelederId: UUID,
    val sykmeldtFnr: String,
    val orgnummer: String,
    val narmestelederFnr: String,
    val narmestelederTelefonnummer: String,
    val narmestelederEpost: String,
    val aktivFom: LocalDate,
    val aktivTom: LocalDate?,
    val arbeidsgiverForskutterer: Boolean?,
    /** Source publication time for an employment-ended revocation; null for other Leesah changes. */
    val sourceEmploymentRevocationAt: Instant? = null,
)

data class LeesahNarmestelederrelasjonRecord(
    val partition: Int,
    val offset: Long,
    val relasjon: LeesahNarmestelederrelasjon,
)
