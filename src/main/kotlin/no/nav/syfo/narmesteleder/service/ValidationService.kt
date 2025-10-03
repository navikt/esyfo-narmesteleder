package no.nav.syfo.narmesteleder.service

import io.ktor.server.plugins.BadRequestException
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.AaregClientException
import no.nav.syfo.application.auth.BrukerPrincipal
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.api.v1.NarmesteLederRelasjonerWrite
import no.nav.syfo.narmesteleder.api.v1.NarmestelederRelasjonAvkreft
import no.nav.syfo.narmesteleder.api.v1.domain.NarmestelederAktorer
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import no.nav.syfo.util.logger

class ValidationService(
    val pdlService: PdlService,
    val aaregService: AaregService,
    val altinnTilgangerService: no.nav.syfo.altinntilganger.AltinnTilgangerService,
) {
    companion object {
        val logger = logger()
    }

    suspend fun validdateNarmesteleder(
        narmesteLederRelasjonerWrite: NarmesteLederRelasjonerWrite,
        principal: Principal,
    ): NarmestelederAktorer {
        try {
            val innsenderOrgNumber = when (principal) {
                is BrukerPrincipal -> {
                    altinnTilgangerService.validateTilgangToOrganisasjon(
                        principal,
                        narmesteLederRelasjonerWrite.organisasjonsnummer
                    )
                    null
                }

                is OrganisasjonPrincipal -> {
                    maskinportenIdToOrgnumber(principal.ident)
                }
            }
            val sykmeldt = pdlService.getPersonFor(narmesteLederRelasjonerWrite.sykmeldtFnr)
            val leder = pdlService.getPersonFor(narmesteLederRelasjonerWrite.leder.fnr)
            val nlArbeidsforhold = aaregService.findOrgNumbersByPersonIdent(leder.fnr)
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.fnr)

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
        } catch (e: PdlResourceNotFoundException) {
            logger.error("Henting av person(er) feilet {}", e.message)
            throw BadRequestException("Could not find one or both of the persons")
        } catch (e: PdlRequestException) {
            logger.error("Validering av personer feilet {}", e.message)
            throw ApiErrorException.InternalServerErrorException("Error when validating persons")
        } catch (e: ValidateNarmesteLederException) {
            logger.error("Validering av arbeidsforhold feilet {}", e.message)
            throw BadRequestException("Error when validating persons")
        } catch (e: AaregClientException) {
            logger.error("Henting av arbeidsforhold feilet {}", e.message)
            throw ApiErrorException.InternalServerErrorException("Error when validating persons")
        }
    }

    suspend fun validateNarmestelederAvkreft(
        narmestelederRelasjonAvkreft: NarmestelederRelasjonAvkreft,
        principal: Principal,
    ): Person {
        try {
            val innsenderOrgNumber = when (principal) {
                is BrukerPrincipal -> {
                    altinnTilgangerService.validateTilgangToOrganisasjon(
                        principal,
                        narmestelederRelasjonAvkreft.organisasjonsnummer
                    )
                    null
                }

                is OrganisasjonPrincipal -> {
                    maskinportenIdToOrgnumber(principal.ident)
                }
            }
            val sykmeldt = pdlService.getPersonFor(narmestelederRelasjonAvkreft.sykmeldtFnr)
            val sykemeldtArbeidsforhold =
                aaregService.findOrgNumbersByPersonIdent(sykmeldt.fnr)
            validateNarmesteLederAvkreft(
                orgNumberInRequest = narmestelederRelasjonAvkreft.organisasjonsnummer,
                sykemeldtOrgNumbers = sykemeldtArbeidsforhold,
                innsenderOrgNumber = innsenderOrgNumber
            )
            return sykmeldt

        } catch (e: PdlResourceNotFoundException) {
            logger.error("Henting av person(er) feilet {}", e.message)
            throw BadRequestException("Could not find one or both of the persons")
        } catch (e: PdlRequestException) {
            logger.error("Validering av personer feilet {}", e.message)
            throw ApiErrorException.InternalServerErrorException("Error when validating persons")
        } catch (e: ValidateNarmesteLederException) {
            logger.error("Validering av arbeidsforhold feilet {}", e.message)
            throw BadRequestException("Error when validating persons")
        } catch (e: AaregClientException) {
            logger.error("Henting av arbeidsforhold feilet {}", e.message)
            throw ApiErrorException.InternalServerErrorException("Error when validating persons")
        }
    }

//    suspend private fun validateAltTilgang(principal: Principal, orgNumber: String) {
//        val innsenderOrgNumber = when (principal) {
//            is BrukerPrincipal -> {
//                altinnTilgangerService.validateTilgangToOrganisasjon(
//                    principal,
//                    orgNumber
//                )
//                null
//            }
//
//            is OrganisasjonPrincipal -> {
//                maskinportenIdToOrgnumber(principal.ident)
//            }
//        }
//    }
}
