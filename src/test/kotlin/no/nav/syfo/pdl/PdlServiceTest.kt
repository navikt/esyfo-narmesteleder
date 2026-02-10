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
import no.nav.syfo.pdl.client.GetPersonResponse
import no.nav.syfo.pdl.client.IPdlClient
import no.nav.syfo.pdl.client.Ident
import no.nav.syfo.pdl.client.IdentResponse
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.pdl.client.PersonResponse
import no.nav.syfo.pdl.client.ResponseData
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException

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
    })
