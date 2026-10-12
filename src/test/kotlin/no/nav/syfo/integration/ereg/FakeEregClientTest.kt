package no.nav.syfo.integration.ereg

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.util.JsonFixtureLoader

class FakeEregClientTest :
    DescribeSpec({

        describe("FakeEregClient") {
            describe("with default fixture loader") {
                val client = FakeEregClient()

                it("should return organisasjon for known orgnummer") {
                    val result = client.getOrganisasjon("310667633").shouldBeInstanceOf<UpstreamResult.Success<Organisasjon?>>().value

                    result shouldNotBe null
                    result?.organisasjonsnummer shouldBe "310667633"
                    result?.driverVirksomheter?.size shouldBe 3
                }

                it("should return null for unknown orgnummer") {
                    val result = client.getOrganisasjon("unknown-org")

                    result shouldBe UpstreamResult.Success(null)
                }
            }

            describe("with custom fixture loader") {
                val loader = JsonFixtureLoader("classpath:fixtures")
                val client = FakeEregClient(loader)

                it("should load organisasjoner from JSON file") {
                    val result = client.getOrganisasjon("111111111").shouldBeInstanceOf<UpstreamResult.Success<Organisasjon?>>().value

                    result shouldNotBe null
                    result?.organisasjonsnummer shouldBe "111111111"
                    result?.driverVirksomheter?.size shouldBe 1
                }

                it("should return related organisasjon") {
                    val result = client.getOrganisasjon("222222222").shouldBeInstanceOf<UpstreamResult.Success<Organisasjon?>>().value

                    result shouldNotBe null
                    result?.inngaarIJuridiskEnheter?.size shouldBe 1
                    result?.inngaarIJuridiskEnheter?.first()?.organisasjonsnummer shouldBe "111111111"
                }
            }

            describe("with missing fixture file") {
                val loader = JsonFixtureLoader("classpath:nonexistent")
                val client = FakeEregClient(loader)

                it("should return null when fixture file not found") {
                    val result = client.getOrganisasjon("any-org")

                    result shouldBe UpstreamResult.Success(null)
                }
            }

            describe("failure simulation") {
                val client = FakeEregClient()

                it("should return configured failure until cleared") {
                    val failure = UpstreamFailure(EREG, UpstreamFailureStage.REQUEST, null, RuntimeException("Test error"))
                    client.setFailure(failure)

                    client.getOrganisasjon("310667633") shouldBe UpstreamResult.Failure(failure)
                    client.getOrganisasjon("unknown-org") shouldBe UpstreamResult.Failure(failure)
                    client.clearFailure()
                    client.getOrganisasjon("310667633").shouldBeInstanceOf<UpstreamResult.Success<Organisasjon?>>().value shouldNotBe null
                }
            }
        }
    })
