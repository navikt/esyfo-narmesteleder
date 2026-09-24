package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.Navn
import no.nav.syfo.ereg.client.Organisasjon
import no.nav.syfo.ident.OrganizationNumber

class EregNarmestelederrelasjonOrganizationTest :
    DescribeSpec({
        val eregService = mockk<EregService>()
        val organization = EregNarmestelederrelasjonOrganization(eregService)
        val orgNumber = OrganizationNumber("123456789")

        it("returns the preferred nonblank organization name") {
            coEvery { eregService.getOrganization(orgNumber.value) } returns
                Organisasjon(orgNumber.value, Navn(sammensattnavn = "Organization"))

            organization.findName(orgNumber) shouldBe "Organization"
        }

        it("returns null when Ereg cannot find the organization") {
            coEvery { eregService.getOrganization(orgNumber.value) } throws
                ApiErrorException.BadRequestException(type = no.nav.syfo.application.api.ErrorType.ORGANIZATION_NOT_FOUND)

            organization.findName(orgNumber) shouldBe null
        }

        listOf(null, " ", "").forEach { preferredName ->
            it("returns null for a missing or blank preferred organization name: ${preferredName ?: "missing"}") {
                coEvery { eregService.getOrganization(orgNumber.value) } returns
                    Organisasjon(orgNumber.value, Navn(sammensattnavn = preferredName))

                organization.findName(orgNumber) shouldBe null
            }
        }

        it("propagates Ereg internal server failures") {
            val failure = ApiErrorException.InternalServerErrorException()
            coEvery { eregService.getOrganization(orgNumber.value) } throws failure

            shouldThrow<ApiErrorException.InternalServerErrorException> {
                organization.findName(orgNumber)
            } shouldBe failure
        }
    })
