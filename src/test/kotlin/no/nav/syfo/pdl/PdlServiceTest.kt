package no.nav.syfo.pdl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.pdl.client.GetPersonBolkResponse
import no.nav.syfo.pdl.client.GetPersonResponse
import no.nav.syfo.pdl.client.HentIdenterBolk
import no.nav.syfo.pdl.client.HentPersonBolk
import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident
import no.nav.syfo.pdl.client.IdentResponse
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.pdl.client.PdlIdent
import no.nav.syfo.pdl.client.PersonBolkResponseData
import no.nav.syfo.pdl.client.PersonResponse
import no.nav.syfo.pdl.client.ResponseData
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
import no.nav.syfo.pdl.client.Person as PdlClientPerson

class PdlServiceTest :
    DescribeSpec({

        val pdlClient = mockk<IPdlClient>()
        val pdlCache = mockk<PdlCache>(relaxed = true)
        val pdlService = PdlService(pdlClient, pdlCache)

        beforeTest {
            clearAllMocks()

            coEvery { pdlCache.getPerson(any()) } returns null
        }

        fun getPersonResponse(navn: List<Navn>, identer: List<Ident>) = GetPersonResponse(
            data = ResponseData(
                person = PersonResponse(navn = navn),
                identer = IdentResponse(identer = identer)
            ),
            errors = null
        )
        describe("getPersonFor") {
            it("should return person when PDL returns valid data") {
                val fnr = "12345678901"
                val navn = Navn(fornavn = "Test", mellomnavn = null, etternavn = "Person")
                val ident = Ident(ident = fnr, gruppe = "FOLKEREGISTERIDENT")

                coEvery { pdlClient.getPerson(fnr) } returns getPersonResponse(listOf(navn), listOf(ident))

                val result = pdlService.getPersonFor(fnr)

                result.nationalIdentificationNumber shouldBe fnr
                result.name shouldBe navn
                coVerify(exactly = 1) { pdlClient.getPerson(fnr) }
            }

            it("should pass through exception when PDL client throws exception") {
                val fnr = "12345678901"
                val exception = PdlRequestException("PDL error")

                coEvery { pdlClient.getPerson(fnr) } throws exception

                shouldThrow<PdlRequestException> {
                    pdlService.getPersonFor(fnr)
                }

                coVerify(exactly = 1) { pdlClient.getPerson(fnr) }
            }

            it("should pass through throw PdlPersonMissingPropertiesException when fnr is null") {
                val fnr = "12345678901"
                val navn = Navn(fornavn = "Test", mellomnavn = null, etternavn = "Person")

                coEvery { pdlClient.getPerson(fnr) } returns getPersonResponse(listOf(navn), emptyList())
                shouldThrow<PdlResourceNotFoundException> {
                    pdlService.getPersonFor(fnr)
                }
            }

            it("should throw PdlPersonMissingPropertiesException when navn is null") {
                val fnr = "12345678901"
                val ident = Ident(ident = fnr, gruppe = "FOLKEREGISTERIDENT")
                coEvery { pdlClient.getPerson(fnr) } returns getPersonResponse(emptyList(), listOf(ident))

                shouldThrow<PdlResourceNotFoundException> {
                    pdlService.getPersonFor(fnr)
                }
            }

            it("should throw IllegalStateException when person is null") {
                val fnr = "12345678901"
                val ident = Ident(ident = fnr, gruppe = "FOLKEREGISTERIDENT")
                val response = GetPersonResponse(
                    data = ResponseData(
                        person = null,
                        identer = IdentResponse(identer = listOf(ident))
                    ),
                    errors = null
                )

                coEvery { pdlClient.getPerson(fnr) } returns response

                shouldThrow<PdlResourceNotFoundException> {
                    pdlService.getPersonFor(fnr)
                }
            }
        }

        describe("getPersonOrThrowApiError") {
            it("should return person when PDL returns valid data") {
                val fnr = "12345678901"
                val navn = Navn(fornavn = "Test", mellomnavn = null, etternavn = "Person")
                val ident = Ident(ident = fnr, gruppe = "FOLKEREGISTERIDENT")

                coEvery { pdlClient.getPerson(fnr) } returns getPersonResponse(listOf(navn), listOf(ident))

                val result = pdlService.getPersonOrThrowApiError(fnr)

                result.nationalIdentificationNumber shouldBe fnr
                result.name shouldBe navn
                coVerify(exactly = 1) { pdlClient.getPerson(fnr) }
            }

            it("should convert PdlResourceNotFoundException to BadRequestException") {
                val fnr = "12345678901"

                coEvery { pdlClient.getPerson(fnr) } throws PdlResourceNotFoundException("Not found")

                shouldThrow<ApiErrorException.BadRequestException> {
                    pdlService.getPersonOrThrowApiError(fnr)
                }

                coVerify(exactly = 1) { pdlClient.getPerson(fnr) }
            }

            it("should convert PdlRequestException to InternalServerErrorException") {
                val fnr = "12345678901"

                coEvery { pdlClient.getPerson(fnr) } throws PdlRequestException("PDL error")

                shouldThrow<ApiErrorException.InternalServerErrorException> {
                    pdlService.getPersonOrThrowApiError(fnr)
                }

                coVerify(exactly = 1) { pdlClient.getPerson(fnr) }
            }
        }

        describe("getPersonsBolk") {
            fun bolkResponse(vararg entries: Triple<String, PdlClientPerson?, String>) = GetPersonBolkResponse(
                data = PersonBolkResponseData(
                    hentPersonBolk = entries.map { (ident, person, code) ->
                        HentPersonBolk(ident = ident, person = person, code = code)
                    },
                    hentIdenterBolk = entries.map { (ident, _, _) ->
                        HentIdenterBolk(
                            ident = ident,
                            identer = listOf(PdlIdent(ident = ident, gruppe = "FOLKEREGISTERIDENT")),
                            code = "ok",
                        )
                    },
                ),
                errors = null,
            )

            it("should return map with Person when bolk returns code ok") {
                val fnr = "12345678901"
                val navn = Navn(fornavn = "Test", mellomnavn = null, etternavn = "Person")

                coEvery { pdlClient.getPersonBolk(listOf(fnr)) } returns
                    bolkResponse(Triple(fnr, PdlClientPerson(navn = listOf(navn)), "ok"))

                val result = pdlService.getPersonsBolk(listOf(fnr))

                result[fnr]?.name shouldBe navn
                result[fnr]?.nationalIdentificationNumber shouldBe fnr
                coVerify(exactly = 1) { pdlClient.getPersonBolk(listOf(fnr)) }
            }

            it("should return null in map when bolk code is not ok") {
                val fnr = "12345678901"

                coEvery { pdlClient.getPersonBolk(listOf(fnr)) } returns
                    bolkResponse(Triple(fnr, null, "not_found"))

                val result = pdlService.getPersonsBolk(listOf(fnr))

                result[fnr] shouldBe null
            }

            it("should return null in map when person is null despite ok code") {
                val fnr = "12345678901"

                coEvery { pdlClient.getPersonBolk(listOf(fnr)) } returns
                    bolkResponse(Triple(fnr, null, "ok"))

                val result = pdlService.getPersonsBolk(listOf(fnr))

                result[fnr] shouldBe null
            }

            it("should return map with multiple entries") {
                val fnr1 = "12345678901"
                val fnr2 = "98765432109"
                val navn1 = Navn(fornavn = "Ola", mellomnavn = null, etternavn = "Nordmann")

                coEvery { pdlClient.getPersonBolk(listOf(fnr1, fnr2)) } returns
                    bolkResponse(
                        Triple(fnr1, PdlClientPerson(navn = listOf(navn1)), "ok"),
                        Triple(fnr2, null, "not_found"),
                    )

                val result = pdlService.getPersonsBolk(listOf(fnr1, fnr2))

                result[fnr1]?.name shouldBe navn1
                result[fnr2] shouldBe null
            }

            it("should throw PdlRequestException when client throws") {
                val fnrs = listOf("12345678901")

                coEvery { pdlClient.getPersonBolk(fnrs) } throws PdlRequestException("PDL error")

                shouldThrow<PdlRequestException> {
                    pdlService.getPersonsBolk(fnrs)
                }
            }
        }
    })
