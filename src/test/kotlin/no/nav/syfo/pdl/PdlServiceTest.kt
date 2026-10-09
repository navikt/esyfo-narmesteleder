package no.nav.syfo.pdl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.integration.pdl.GetPersonResponse
import no.nav.syfo.integration.pdl.Ident
import no.nav.syfo.integration.pdl.IdentResponse
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlRequestException
import no.nav.syfo.integration.pdl.PdlResourceNotFoundException
import no.nav.syfo.integration.pdl.PersonResponse
import no.nav.syfo.integration.pdl.ResponseData

class PdlServiceTest :
    DescribeSpec({

        val pdlClient = mockk<PdlClient>()
        val pdlService = PdlService(pdlClient)

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
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

                result.nationalIdentificationNumber.value shouldBe fnr
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
    })
