package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
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
)

/** A Leesah relation that has passed validation and is ready to be stored. */
data class ValidLeesahNarmestelederrelasjon(
    val narmestelederId: UUID,
    val sykmeldtFnr: PersonIdent,
    val orgnummer: OrganizationNumber,
    val narmestelederFnr: PersonIdent,
    val narmestelederTelefonnummer: String,
    val narmestelederEpost: String,
    val aktivFom: LocalDate,
    val aktivTom: LocalDate?,
    val arbeidsgiverForskutterer: Boolean?,
)

data class LeesahNarmestelederrelasjonRecord(
    val partition: Int,
    val offset: Long,
    val relasjon: LeesahNarmestelederrelasjon,
)
