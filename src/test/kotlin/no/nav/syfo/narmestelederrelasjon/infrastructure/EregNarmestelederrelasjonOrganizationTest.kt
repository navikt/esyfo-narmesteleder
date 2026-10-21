package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.integration.ereg.Navn
import no.nav.syfo.integration.ereg.Organisasjon
import no.nav.syfo.integration.ereg.RecordingEregClient
import no.nav.syfo.narmestelederrelasjon.application.get.OrganizationNameResult
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import kotlin.coroutines.cancellation.CancellationException

class EregNarmestelederrelasjonOrganizationTest :
    FunSpec({
        val orgNumber = OrganizationNumber("910000001")

        test("returns the preferred nonblank organization name") {
            val organization = EregNarmestelederrelasjonOrganization(
                RecordingEregClient {
                    UpstreamResult.Success(Organisasjon(orgNumber.value, Navn(sammensattnavn = "Organization", navnelinje1 = "Other name")))
                },
            )
            organization.findName(orgNumber) shouldBe OrganizationNameResult.Found("Organization")
        }

        test("returns the first name line when the combined name is absent") {
            val organization = EregNarmestelederrelasjonOrganization(
                RecordingEregClient {
                    UpstreamResult.Success(Organisasjon(orgNumber.value, Navn(navnelinje1 = "Organization")))
                },
            )
            organization.findName(orgNumber) shouldBe OrganizationNameResult.Found("Organization")
        }

        test("returns Missing when Ereg cannot find the organization") {
            EregNarmestelederrelasjonOrganization(RecordingEregClient { UpstreamResult.Success(null) })
                .findName(orgNumber) shouldBe OrganizationNameResult.Missing
        }

        test("returns Missing when Ereg has no name object") {
            EregNarmestelederrelasjonOrganization(RecordingEregClient { UpstreamResult.Success(Organisasjon(orgNumber.value)) })
                .findName(orgNumber) shouldBe OrganizationNameResult.Missing
        }

        listOf("missing" to null, "blank" to " ", "empty" to "").forEach { (case, preferredName) ->
            test("returns Missing for a $case preferred organization name") {
                val organization = EregNarmestelederrelasjonOrganization(
                    RecordingEregClient {
                        UpstreamResult.Success(Organisasjon(orgNumber.value, Navn(sammensattnavn = preferredName)))
                    },
                )
                organization.findName(orgNumber) shouldBe OrganizationNameResult.Missing
            }
        }

        test("a blank preferred name is Missing even when another name line is present") {
            val organization = EregNarmestelederrelasjonOrganization(
                RecordingEregClient {
                    UpstreamResult.Success(Organisasjon(orgNumber.value, Navn(sammensattnavn = " ", navnelinje1 = "Other name")))
                },
            )
            organization.findName(orgNumber) shouldBe OrganizationNameResult.Missing
        }

        test("propagates an upstream failure as Unavailable without wrapping its cause") {
            val failure = UpstreamFailure(UpstreamName("ereg"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val organization = EregNarmestelederrelasjonOrganization(RecordingEregClient { UpstreamResult.Failure(failure) })

            organization.findName(orgNumber).shouldBeInstanceOf<OrganizationNameResult.Unavailable>()
                .failure shouldBeSameInstanceAs failure
        }

        test("propagates cancellation from the client") {
            val cancelled = CancellationException("Request cancelled")
            val organization = EregNarmestelederrelasjonOrganization(RecordingEregClient { throw cancelled })

            shouldThrow<CancellationException> { organization.findName(orgNumber) } shouldBeSameInstanceAs cancelled
        }
    })
