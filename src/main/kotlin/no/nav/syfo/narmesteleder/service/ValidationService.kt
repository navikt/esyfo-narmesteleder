package no.nav.syfo.narmesteleder.service

import no.nav.syfo.aareg.AaregService
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.api.v1.domain.NarmestelederAktorer
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.util.logger

class ValidationService(
    val pdlService: PdlService,
    val aaregService: AaregService,
    val altinnTilgangerService: no.nav.syfo.altinntilganger.AltinnTilgangerService,
    val dinesykmeldteService: DinesykmeldteService,
) {
    companion object {
        val logger = logger()
    }

    suspend fun validateNarmesteleder(
        narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite,
        principal: Principal,
    ): NarmestelederAktorer {
        try {
            val innsenderOrgNumber = validateAltTilgang(principal, narmesteLederRelasjonerWrite.organisasjonsnummer)
            val sykmeldt = pdlService.getPersonOrThrowApiError(narmesteLederRelasjonerWrite.sykmeldtFnr)
            val leder = pdlService.getPersonOrThrowApiError(narmesteLederRelasjonerWrite.leder.fnr)
            val nlArbeidsforhold = aaregService.findOrgNumbersByPersonIdent(leder.fnr)
                .filter { it.key == narmesteLederRelasjonerWrite.organisasjonsnummer }
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.fnr)
                    .filter { it.key == narmesteLederRelasjonerWrite.organisasjonsnummer }
            validataActiveSykmelding(sykmeldt.fnr, narmesteLederRelasjonerWrite.organisasjonsnummer)
            validateNarmesteLeder(
                orgNumberInRequest = narmesteLederRelasjonerWrite.organisasjonsnummer,
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                narmesteLederOrgNumbers = nlArbeidsforhold,
                innsenderOrgNumber = innsenderOrgNumber
            )
            return NarmestelederAktorer(
                sykmeldt = sykmeldt,
                leder = leder,
            )
        } catch (e: ValidateNarmesteLederException) {
            logger.error("Validering av arbeidsforhold feilet {}", e.message)
            throw ApiErrorException.BadRequestException(
                "Error validating employment relationship for the given organization number"
            )
        } catch (smExc: ValidateActiveSykmeldingException) {
            logger.error(
                "No active sykmelding in orgnummer ${narmesteLederRelasjonerWrite.organisasjonsnummer}",
                smExc.message
            )
            throw ApiErrorException.BadRequestException(
                "No active sick leave certificate found for the given organization number"
            )
        }
    }

    suspend fun validataActiveSykmelding(fnr: String, orgnummer: String): Boolean =
        if (!dinesykmeldteService.getIsActiveSykmelding(fnr, orgnummer)) {
            throw ValidateActiveSykmeldingException("Sykmelding er ikke aktiv")
        } else true

    suspend fun validateNarmestelederAvkreft(
        narmestelederRelasjonAvkreft: NarmestelederRelasjonAvkreft,
        principal: Principal,
    ): Person {
        try {
            val innsenderOrgNumber = validateAltTilgang(principal, narmestelederRelasjonAvkreft.organisasjonsnummer)
            val sykmeldt = pdlService.getPersonOrThrowApiError(narmestelederRelasjonAvkreft.sykmeldtFnr)
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.fnr)
            validateNarmesteLederAvkreft(
                orgNumberInRequest = narmestelederRelasjonAvkreft.organisasjonsnummer,
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                innsenderOrgNumber = innsenderOrgNumber
            )
            return sykmeldt

        } catch (e: ValidateNarmesteLederException) {
            logger.error("Validering av arbeidsforhold feilet {}", e.message)
            throw ApiErrorException.BadRequestException("Error when validating persons")
        }
    }

    suspend private fun validateAltTilgang(principal: Principal, orgNumber: String): String? {
        return when (principal) {
            is BrukerPrincipal -> {
                altinnTilgangerService.validateTilgangToOrganisasjon(
                    principal,
                    orgNumber
                )
                null
            }

            is OrganisasjonPrincipal -> {
                maskinportenIdToOrgnumber(principal.ident)
            }
        }
    }
}
