package no.nav.syfo.integration.aareg

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.syfo.util.JsonFixtureLoader
import java.time.LocalDate

class FakeAaregClientTest :
    DescribeSpec({

        describe("FakeAaregClient") {
            describe("with default fixture loader") {
                val client = FakeAaregClient()

                it("should load arbeidsforhold from default JSON file") {
                    // From src/main/resources/fake-clients/aareg/arbeidsforhold.json
                    client.arbeidsForholdForIdent.keys shouldContainExactlyInAnyOrder listOf(
                        "15436803416",
                        "13468329780",
                        "01518721689"
                    )
                }

                it("should parse arbeidsforhold as pairs of (orgnummer, juridiskOrgnummer)") {
                    val arbeidsforhold = client.arbeidsForholdForIdent["15436803416"]!!

                    arbeidsforhold shouldHaveSize 2
                    arbeidsforhold shouldContainExactlyInAnyOrder listOf(
                        "215649202" to "310667633",
                        "972674818" to "963743254"
                    )
                }
            }

            describe("with custom fixture loader") {
                val loader = JsonFixtureLoader("classpath:fixtures")
                val client = FakeAaregClient(loader)

                it("should load arbeidsforhold from custom JSON file") {
                    client.arbeidsForholdForIdent.keys shouldContainExactlyInAnyOrder listOf(
                        "test-fnr-001",
                        "test-fnr-002"
                    )
                }

                it("should parse arbeidsforhold correctly") {
                    client.arbeidsForholdForIdent["test-fnr-001"] shouldBe listOf("111111111" to "222222222")
                    client.arbeidsForholdForIdent["test-fnr-002"]!! shouldHaveSize 2
                }
            }

            describe("with missing fixture file") {
                val loader = JsonFixtureLoader("classpath:nonexistent")
                val client = FakeAaregClient(loader)

                it("should have empty arbeidsforhold when fixture file not found") {
                    client.arbeidsForholdForIdent.size shouldBe 0
                }
            }

            describe("getArbeidsforhold") {
                val client = FakeAaregClient()

                it("should return AaregArbeidsforholdOversikt for known fnr") {
                    val result = client.getArbeidsforhold("15436803416")

                    result.arbeidsforholdoversikter shouldHaveSize 2
                    result.arbeidsforholdoversikter[0].arbeidssted.getOrgnummer() shouldBe "215649202"
                    result.arbeidsforholdoversikter[0].opplysningspliktig.getJuridiskOrgnummer() shouldBe "310667633"
                }

                it("should return empty response for unknown fnr") {
                    val result = client.getArbeidsforhold("unknown-fnr")

                    result.arbeidsforholdoversikter shouldHaveSize 0
                }

                it("should reflect changes made to arbeidsForHoldForIdent") {
                    client.arbeidsForholdForIdent["new-fnr"] = listOf("999999999" to "888888888")

                    val result = client.getArbeidsforhold("new-fnr")

                    result.arbeidsforholdoversikter shouldHaveSize 1
                    result.arbeidsforholdoversikter[0].arbeidssted.getOrgnummer() shouldBe "999999999"
                }
            }

            describe("failure simulation") {
                val client = FakeAaregClient()

                it("should throw configured failure") {
                    val expectedException = AaregClientException("Test error", RuntimeException())
                    client.setFailure(expectedException)

                    val exception = runCatching { client.getArbeidsforhold("15436803416") }.exceptionOrNull()

                    exception shouldBe expectedException
                }

                it("should work normally after clearing failure") {
                    client.setFailure(RuntimeException("Should be cleared"))
                    client.clearFailure()

                    val result = client.getArbeidsforhold("15436803416")

                    result.arbeidsforholdoversikter shouldHaveSize 2
                }
            }

            describe("employment history") {
                it("should return the same fixture data and reflect existing pair overrides") {
                    val client = FakeAaregClient(JsonFixtureLoader("classpath:fixtures"))
                    client.getArbeidsforholdHistorikk("test-fnr-001") shouldBe client.getArbeidsforhold("test-fnr-001")
                    client.arbeidsForholdForIdent["test-fnr-001"] = listOf("910000001" to "910000002")
                    client.getArbeidsforholdHistorikk("test-fnr-001") shouldBe client.getArbeidsforhold("test-fnr-001")
                    client.getArbeidsforholdHistorikk("unknown-fnr") shouldBe AaregArbeidsforholdOversikt()
                }

                it("should support seeding periods without changing existing getArbeidsforhold") {
                    val client = FakeAaregClient(JsonFixtureLoader("classpath:nonexistent"))
                    client.arbeidsForholdForIdent["test-fnr"] = listOf("910000001" to "910000002")
                    val current = client.getArbeidsforhold("test-fnr")
                    val history = current.copy(
                        arbeidsforholdoversikter = current.arbeidsforholdoversikter.map {
                            it.copy(startdato = LocalDate.of(2025, 1, 1), sluttdato = LocalDate.of(2026, 3, 31))
                        },
                    )
                    client.setEmploymentHistory("test-fnr", history)

                    client.getArbeidsforholdHistorikk("test-fnr") shouldBe history
                    client.getArbeidsforhold("test-fnr") shouldBe current
                }

                it("should honor and clear failures even for seeded history") {
                    val client = FakeAaregClient(JsonFixtureLoader("classpath:nonexistent"))
                    val history = client.createArbeidsforholdOversikt("910000001" to "910000002")
                    client.setEmploymentHistory("test-fnr", history)
                    val failure = AaregClientException("Test error")
                    client.setFailure(failure)

                    shouldThrow<AaregClientException> { client.getArbeidsforholdHistorikk("test-fnr") } shouldBe failure
                    client.clearFailure()
                    client.getArbeidsforholdHistorikk("test-fnr") shouldBe history
                }
            }
        }
    })
