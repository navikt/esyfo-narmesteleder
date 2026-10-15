package no.nav.syfo.pdl

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.integration.pdl.Ident
import no.nav.syfo.integration.pdl.Navn
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.integration.pdl.PdlPerson
import no.nav.syfo.integration.pdl.PdlRequestException
import no.nav.syfo.integration.pdl.PersonResponse
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult

class PdlServiceTest :
    DescribeSpec({

        val pdlClient = mockk<PdlClient>()
        val pdlService = PdlService(pdlClient)

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }
        fun getPersonResponse(navn: List<Navn>, identer: List<Ident>) = UpstreamResult.Success(PdlPerson(PersonResponse(navn), identer))
        describe("getPersonFor") {
            it("should return person when PDL returns valid data") {
                val fnr = "12345678901"
                val navn = Navn(fornavn = "Test", mellomnavn = null, etternavn = "Person")
                val ident = Ident(ident = fnr, gruppe = "FOLKEREGISTERIDENT")

                coEvery { pdlClient.getPerson(fnr) } returns getPersonResponse(listOf(navn), listOf(ident))

                val result = pdlService.getPersonFor(fnr)

                requireNotNull(result).nationalIdentificationNumber.value shouldBe fnr
                result.name shouldBe navn
                coVerify(exactly = 1) { pdlClient.getPerson(fnr) }
            }

            it("bridges a failure through getOrThrow") {
                val fnr = "12345678901"
                val exception = PdlRequestException("PDL error")

                coEvery { pdlClient.getPerson(fnr) } returns UpstreamResult.Failure(UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.REQUEST, null, exception))

                shouldThrow<UpstreamRequestException> {
                    pdlService.getPersonFor(fnr)
                }.cause shouldBe exception

                coVerify(exactly = 1) { pdlClient.getPerson(fnr) }
            }

            it("returns null without a folkeregisterident") {
                val fnr = "12345678901"
                val navn = Navn(fornavn = "Test", mellomnavn = null, etternavn = "Person")

                coEvery { pdlClient.getPerson(fnr) } returns getPersonResponse(listOf(navn), emptyList())
                pdlService.getPersonFor(fnr) shouldBe null
            }

            it("returns null without a name") {
                val fnr = "12345678901"
                val ident = Ident(ident = fnr, gruppe = "FOLKEREGISTERIDENT")
                coEvery { pdlClient.getPerson(fnr) } returns getPersonResponse(emptyList(), listOf(ident))

                pdlService.getPersonFor(fnr) shouldBe null
            }

            it("returns null when the person was not found") {
                val fnr = "12345678901"
                val response = UpstreamResult.Success(null)

                coEvery { pdlClient.getPerson(fnr) } returns response

                pdlService.getPersonFor(fnr) shouldBe null
            }
        }
    })
