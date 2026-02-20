package no.nav.syfo.ereg.client

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.util.JsonFixtureLoader

class FakeEregClientTest :
    DescribeSpec({

        describe("FakeEregClient") {
            describe("with default fixture loader") {
                val client = FakeEregClient()

                it("should return organisasjon for known orgnummer") {
                    val result = client.getOrganisasjon("310667633")

                    result shouldNotBe null
                    result?.organisasjonsnummer shouldBe "310667633"
                    result?.driverVirksomheter?.size shouldBe 3
                }

                it("should return null for unknown orgnummer") {
                    val result = client.getOrganisasjon("unknown-org")

                    result shouldBe null
                }
            }

            describe("with custom fixture loader") {
                val loader = JsonFixtureLoader("classpath:fixtures")
                val client = FakeEregClient(loader)

                it("should load organisasjoner from JSON file") {
                    val result = client.getOrganisasjon("111111111")

                    result shouldNotBe null
                    result?.organisasjonsnummer shouldBe "111111111"
                    result?.driverVirksomheter?.size shouldBe 1
                }

                it("should return related organisasjon") {
                    val result = client.getOrganisasjon("222222222")

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

                    result shouldBe null
                }
            }

            describe("failure simulation") {
                val client = FakeEregClient()

                it("should throw configured failure") {
                    val expectedException = RuntimeException("Test error")
                    client.setFailure(expectedException)

                    val exception = runCatching { client.getOrganisasjon("310667633") }.exceptionOrNull()

                    exception shouldBe expectedException
                    client.clearFailure()
                }
            }
        }
    })
