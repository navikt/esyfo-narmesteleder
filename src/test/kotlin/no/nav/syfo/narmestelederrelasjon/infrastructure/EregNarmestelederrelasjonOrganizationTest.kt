package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.exception.UpstreamRequestException
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.integration.ereg.Navn
import no.nav.syfo.integration.ereg.Organisasjon

class EregNarmestelederrelasjonOrganizationTest :
    FunSpec({
        val orgNumber = OrganizationNumber("910000001")

        test("returns the preferred nonblank organization name") {
            val organization = EregNarmestelederrelasjonOrganization(
                StubOrganizationClient {
                    Organisasjon(orgNumber.value, Navn(sammensattnavn = "Organization"))
                },
            )
            organization.findName(orgNumber) shouldBe "Organization"
        }

        test("returns null when Ereg cannot find the organization") {
            EregNarmestelederrelasjonOrganization(StubOrganizationClient { null }).findName(orgNumber) shouldBe null
        }

        listOf(null, " ", "").forEach { preferredName ->
            test("returns null for a missing or blank preferred organization name: ${preferredName ?: "missing"}") {
                val organization = EregNarmestelederrelasjonOrganization(
                    StubOrganizationClient {
                        Organisasjon(orgNumber.value, Navn(sammensattnavn = preferredName))
                    },
                )
                organization.findName(orgNumber) shouldBe null
            }
        }

        test("maps upstream failures to the existing error contract") {
            val upstreamFailure = UpstreamRequestException("Ereg unavailable")
            val organization = EregNarmestelederrelasjonOrganization(StubOrganizationClient { throw upstreamFailure })
            val failure = shouldThrow<ApiErrorException.InternalServerErrorException> { organization.findName(orgNumber) }
            failure.message shouldBe "Could not get organization"
            failure.type shouldBe ErrorType.UPSTREAM_SERVICE_UNAVAILABLE
            failure.cause shouldBe upstreamFailure
        }
    })

private class StubOrganizationClient(private val result: () -> Organisasjon?) : EregClient {
    override suspend fun getOrganisasjon(orgnummer: String): Organisasjon? = result()
}
