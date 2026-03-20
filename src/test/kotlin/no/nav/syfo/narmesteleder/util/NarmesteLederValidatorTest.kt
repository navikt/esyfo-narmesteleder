package no.nav.syfo.narmesteleder.util

import createRandomValidOrgNumbers
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import linemanager
import no.nav.syfo.aareg.Arbeidsforhold
import no.nav.syfo.aareg.client.ArbeidsstedType
import no.nav.syfo.aareg.client.OpplysningspliktigType
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.service.validateLinemanagerLastName
import no.nav.syfo.narmesteleder.service.validateNarmesteLederAvkreft
import no.nav.syfo.narmesteleder.service.validateSmAndNlArbeidsforhold
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn

class NarmesteLederValidatorTest :
    DescribeSpec({
        lateinit var randomOrgNumbers: List<String>
        lateinit var nlOrgNumbers: Map<String, String>
        lateinit var arbeidsforhold: Arbeidsforhold
        beforeTest {
            randomOrgNumbers = createRandomValidOrgNumbers(prefix = "")
            nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
            arbeidsforhold = Arbeidsforhold(
                orgnummer = randomOrgNumbers.first(),
                arbeidsstedType = ArbeidsstedType.Underenhet,
                opplysningspliktigOrgnummer = randomOrgNumbers.last(),
                opplysningspliktigType = OpplysningspliktigType.Hovedenhet,
            )
        }
        describe("validateNarmesteLeder") {
            describe("organization number matches for sykemeldt, nl and innsender") {
                it("It should not throw when sykmeldt is only org number used for all parties") {
                    shouldNotThrowAny {
                        validateSmAndNlArbeidsforhold(
                            sykemeldtOrgNumbers = nlOrgNumbers,
                            narmesteLederOrgNumbers = nlOrgNumbers,
                            orgNumberInRequest = nlOrgNumbers.keys.first(),
                        )
                    }
                }

                it("Should not throw when sykmeldt has at least one matching org number with the other parties") {
                    shouldNotThrowAny {
                        validateSmAndNlArbeidsforhold(
                            sykemeldtOrgNumbers = mapOf(randomOrgNumbers.first() to createRandomValidOrgNumbers(prefix = "").first()),
                            // nlOrgNumbers also contains randomOrgNumbers.first()
                            narmesteLederOrgNumbers = nlOrgNumbers,
                            orgNumberInRequest = nlOrgNumbers.keys.first()
                        )
                    }
                }
            }

            describe("Mismatch in organization number between parties") {
                it("Should throw BadRequestException if NL is not within sykmeldt orgs") {
                    val organizationPrincipal = SystemPrincipal(
                        "0192:${nlOrgNumbers.keys.first()}",
                        "token",
                        "0192:systemOwner",
                        "systemUserId"
                    )
                    shouldThrow<ApiErrorException.BadRequestException> {
                        validateSmAndNlArbeidsforhold(
                            sykemeldtOrgNumbers = nlOrgNumbers,
                            narmesteLederOrgNumbers = mapOf(randomOrgNumbers[2] to randomOrgNumbers[3]),
                            orgNumberInRequest = randomOrgNumbers.first(),
                        )
                    }
                }

                it("Should throw BadRequestException if payload org is not within sykemldt orgs") {
                    val organizationPrincipal = SystemPrincipal(
                        "0192:${nlOrgNumbers.keys.first()}",
                        "token",
                        "0192:systemOwner",
                        "systemUserId"
                    )
                    shouldThrow<ApiErrorException.BadRequestException> {
                        validateSmAndNlArbeidsforhold(
                            sykemeldtOrgNumbers = nlOrgNumbers,
                            narmesteLederOrgNumbers = nlOrgNumbers,
                            orgNumberInRequest = randomOrgNumbers[2],
                        )
                    }
                }

                it("Should throw BadRequestException if no one is within the same org") {
                    shouldThrow<ApiErrorException.BadRequestException> {
                        validateSmAndNlArbeidsforhold(
                            sykemeldtOrgNumbers = nlOrgNumbers,
                            narmesteLederOrgNumbers = mapOf(randomOrgNumbers[2] to randomOrgNumbers[3]),
                            orgNumberInRequest = randomOrgNumbers[3],
                        )
                    }
                }

                it("Should throw BadRequestException exception if no organizations are found for sykemeldt") {
                    shouldThrow<ApiErrorException.BadRequestException> {
                        validateSmAndNlArbeidsforhold(
                            sykemeldtOrgNumbers = emptyMap(),
                            narmesteLederOrgNumbers = nlOrgNumbers,
                            orgNumberInRequest = nlOrgNumbers.keys.first(),
                        )
                    }
                }

                it("Should throw BadRequestException exception if no organizations are found for nærmeste leder") {
                    shouldThrow<ApiErrorException.BadRequestException> {
                        validateSmAndNlArbeidsforhold(
                            sykemeldtOrgNumbers = nlOrgNumbers,
                            narmesteLederOrgNumbers = emptyMap(),
                            orgNumberInRequest = nlOrgNumbers.keys.first(),
                        )
                    }
                }
            }
        }

        describe("validateNarmesteLederAvkreft") {
            describe("organization number matches for sykemeldt and innsender") {
                it("It should not throw when sykemeldt is only org number used for all parties") {
                    shouldNotThrowAny {
                        validateNarmesteLederAvkreft(
                            sykmeltArbeidsforhold = listOf(arbeidsforhold),
                            orgNumberInRequest = arbeidsforhold.orgnummer
                        )
                    }
                }

                it("Should not throw when sykemeldt has at least one matching org number with the other parties") {
                    shouldNotThrowAny {
                        validateNarmesteLederAvkreft(
                            sykmeltArbeidsforhold = listOf(
                                arbeidsforhold,
                                arbeidsforhold.copy(
                                    orgnummer = randomOrgNumbers[2],
                                    opplysningspliktigOrgnummer = randomOrgNumbers[3],
                                ),
                            ),
                            orgNumberInRequest = arbeidsforhold.orgnummer
                        )
                    }
                }
            }

            describe("Mismatch in organization number between parties") {
                it("Should throw BadRequestException if payload org is not within sykemeldt orgs") {
                    shouldThrow<ApiErrorException.BadRequestException> {
                        validateNarmesteLederAvkreft(
                            sykmeltArbeidsforhold = listOf(arbeidsforhold),
                            orgNumberInRequest = randomOrgNumbers[2],
                        )
                    }
                }

                it("Should throw BadRequestException exception if no organizations are found for sykemeldt") {
                    shouldThrow<ApiErrorException.BadRequestException> {
                        validateNarmesteLederAvkreft(
                            sykmeltArbeidsforhold = emptyList<Arbeidsforhold>(),
                            orgNumberInRequest = arbeidsforhold.orgnummer,
                        )
                    }
                }
            }
        }

        describe("validateLinemanagerLastName") {
            it("Should throw BadRequestException if lastname of PdlPerson and manager does not match") {
                val linemanager = linemanager()
                val person = Person(
                    name = Navn(
                        fornavn = "Firsname",
                        mellomnavn = null,
                        etternavn = linemanager.manager.lastName.reversed(),
                    ),
                    nationalIdentificationNumber = linemanager.manager.nationalIdentificationNumber,
                )
                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    validateLinemanagerLastName(
                        person,
                        linemanager
                    )
                }
                exception.message shouldBe "Last name for linemanager does not correspond with registered value for the given national identification number"
            }

            it("Should not throw BadRequestException if lastname of PdlPerson and manager matches case insensitively") {
                val linemanager = linemanager()
                val person = Person(
                    name = Navn(
                        fornavn = "Firsname",
                        mellomnavn = null,
                        etternavn = linemanager.manager.lastName.lowercase(),
                    ),
                    nationalIdentificationNumber = linemanager.manager.nationalIdentificationNumber,
                )
                shouldNotThrow<ApiErrorException.BadRequestException> {
                    validateLinemanagerLastName(
                        person,
                        linemanager
                    )
                }
            }
        }
    })
