package no.nav.syfo.integration.aareg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.util.JsonFixtureLoader

class FakeAaregClientTest :
    FunSpec({
        test("loads employment from the default fixture") {
            FakeAaregClient().arbeidsForholdForIdent.size shouldBe 3
        }

        test("loads and parses a custom fixture") {
            val client = FakeAaregClient(JsonFixtureLoader("classpath:fixtures"))
            client.arbeidsForholdForIdent.keys shouldContainExactlyInAnyOrder listOf("test-fnr-001", "test-fnr-002")
            client.arbeidsForholdForIdent["test-fnr-001"] shouldBe listOf("111111111" to "222222222")
            requireNotNull(client.arbeidsForholdForIdent["test-fnr-002"]) shouldHaveSize 2
        }

        test("has no employment when the fixture is missing") {
            FakeAaregClient(JsonFixtureLoader("classpath:nonexistent")).arbeidsForholdForIdent.size shouldBe 0
        }

        test("returns success with fixture employment") {
            val client = FakeAaregClient(JsonFixtureLoader("classpath:fixtures"))
            val overview = client.getArbeidsforhold("test-fnr-001").shouldBeInstanceOf<UpstreamResult.Success<AaregArbeidsforholdOversikt>>().value
            overview.arbeidsforholdoversikter shouldHaveSize 1
            overview.arbeidsforholdoversikter.single().arbeidssted.getOrgnummer() shouldBe "111111111"
            overview.arbeidsforholdoversikter.single().opplysningspliktig.getJuridiskOrgnummer() shouldBe "222222222"
        }

        test("returns an empty success for an unknown ident") {
            FakeAaregClient().getArbeidsforhold("unknown-fnr") shouldBe UpstreamResult.Success(AaregArbeidsforholdOversikt())
        }

        test("reflects changes to the seeded employment") {
            val client = FakeAaregClient()
            client.arbeidsForholdForIdent["new-fnr"] = listOf("999999999" to "888888888")
            val overview = client.getArbeidsforhold("new-fnr").shouldBeInstanceOf<UpstreamResult.Success<AaregArbeidsforholdOversikt>>().value
            overview.arbeidsforholdoversikter shouldHaveSize 1
            overview.arbeidsforholdoversikter.single().arbeidssted.getOrgnummer() shouldBe "999999999"
        }

        test("returns the configured failure until cleared") {
            val client = FakeAaregClient(JsonFixtureLoader("classpath:fixtures"))
            val failure = UpstreamFailure(AAREG, UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            client.setFailure(failure)
            repeat(2) { client.getArbeidsforhold("test-fnr-001") shouldBe UpstreamResult.Failure(failure) }
            client.clearFailure()
            client.getArbeidsforhold("test-fnr-001").shouldBeInstanceOf<UpstreamResult.Success<AaregArbeidsforholdOversikt>>()
        }
    })
