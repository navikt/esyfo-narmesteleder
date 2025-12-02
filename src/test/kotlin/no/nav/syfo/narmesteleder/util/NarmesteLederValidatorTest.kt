package no.nav.syfo.narmesteleder.util

import addMaskinportenOrgPrefix
import createRandomValidOrgNumbers
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import linemanager
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.service.ValidateNarmesteLederException
import no.nav.syfo.narmesteleder.service.validateLinemanagerLastName
import no.nav.syfo.narmesteleder.service.validateNarmesteLeder
import no.nav.syfo.narmesteleder.service.validateNarmesteLederAvkreft
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn

class NarmesteLederValidatorTest : DescribeSpec({
    lateinit var randomOrgNumbers: List<String>
    lateinit var nlOrgNumbers: Map<String, String>
    lateinit var organizationPrincipal: SystemPrincipal
    beforeTest {
        randomOrgNumbers = createRandomValidOrgNumbers(prefix = "")
        nlOrgNumbers = mapOf(randomOrgNumbers.first() to randomOrgNumbers.last())
        organizationPrincipal = SystemPrincipal(
            addMaskinportenOrgPrefix(nlOrgNumbers.keys.first()),
            "token",
            "0192:systemOwner",
            "systemUserId"
        )
    }
    describe("validateNarmesteLeder") {
        describe("organization number matches for sykemeldt, nl and innsender") {
            it("It should not throw when sykmeldt is only org number used for all parties") {
                shouldNotThrowAny {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
                    )
                }
            }

            it("Should not throw when sykmeldt has at least one matching org number with the other parties") {
                shouldNotThrowAny {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = mapOf(randomOrgNumbers.first() to createRandomValidOrgNumbers(prefix = "").first()),
                        // nlOrgNumbers also contains randomOrgNumbers.first()
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = nlOrgNumbers.keys.first()
                    )
                }
            }
        }

        describe("Mismatch in organization number between parties") {
            it("Should throw ValidateNarmesteLederException if NL is not within sykmeldt orgs") {
                val organizationPrincipal = SystemPrincipal(
                    "0192:${nlOrgNumbers.keys.first()}",
                    "token",
                    "0192:systemOwner",
                    "systemUserId"
                )
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = mapOf(randomOrgNumbers[2] to randomOrgNumbers[3]),
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = randomOrgNumbers.first(),
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException if payload org is not within sykemldt orgs") {
                val organizationPrincipal = SystemPrincipal(
                    "0192:${nlOrgNumbers.keys.first()}",
                    "token",
                    "0192:systemOwner",
                    "systemUserId"
                )
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = randomOrgNumbers[2],
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException if innsender is not within NL org") {
                shouldThrow<ApiErrorException.ForbiddenException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        systemPrincipal = SystemPrincipal(
                            addMaskinportenOrgPrefix(randomOrgNumbers[2]),
                            "token",
                            "0192:systemOwner",
                            "systemUserId"
                        ),
                        orgNumberInRequest = nlOrgNumbers.keys.first()
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException if no one is within the same org") {
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = mapOf(randomOrgNumbers[2] to randomOrgNumbers[3]),
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = randomOrgNumbers[3],
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException exception if no organizations are found for sykemeldt") {
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = emptyMap(),
                        narmesteLederOrgNumbers = nlOrgNumbers,
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException exception if no organizations are found for nærmeste leder") {
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLeder(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        narmesteLederOrgNumbers = emptyMap(),
                        systemPrincipal = organizationPrincipal,
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
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
                    )
                }
            }

            it("Should not throw when sykemeldt has at least one matching org number with the other parties") {
                shouldNotThrowAny {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = nlOrgNumbers.keys.first()
                    )
                }
            }
        }

        describe("Mismatch in organization number between parties") {
            it("Should throw ValidateNarmesteLederException if payload org is not within sykemeldt orgs") {
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = randomOrgNumbers[2],
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException if innsender is not within sykmeldt org") {
                shouldThrow<ApiErrorException.ForbiddenException> {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = nlOrgNumbers,
                        systemPrincipal = SystemPrincipal(
                            addMaskinportenOrgPrefix(randomOrgNumbers[2]),
                            "token",
                            "0192:systemOwner",
                            "systemUserId"
                        ),
                        orgNumberInRequest = nlOrgNumbers.keys.first()
                    )
                }
            }

            it("Should throw ValidateNarmesteLederException exception if no organizations are found for sykemeldt") {
                shouldThrow<ValidateNarmesteLederException> {
                    validateNarmesteLederAvkreft(
                        sykemeldtOrgNumbers = emptyMap(),
                        systemPrincipal = organizationPrincipal,
                        orgNumberInRequest = nlOrgNumbers.keys.first(),
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
                    person, linemanager
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
                    person, linemanager
                )
            }
        }
    }
})
