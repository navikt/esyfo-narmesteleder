package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.LocalDate
import java.util.UUID

/** A validated relation, ready to be inserted or updated in the relation register. */
data class NarmestelederrelasjonUpsert(
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
