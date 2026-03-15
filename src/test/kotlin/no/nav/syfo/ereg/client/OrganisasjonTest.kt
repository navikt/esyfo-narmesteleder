package no.nav.syfo.ereg.client

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.util.JsonFixtureLoader

class OrganisasjonTest :
    DescribeSpec({
        val fixtureLoader = JsonFixtureLoader("classpath:fake-clients/ereg")

        describe("Successfully deserializes from the Ereg API response") {
            it("Deserializes Organisasjon with organisasjonsledd") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("314602374.json")
                organization?.organisasjonsnummer shouldBe "314602374"
                organization?.inngaarIJuridiskEnheter shouldBe null
                organization?.driverVirksomheter shouldBe null
                organization?.bestaarAvOrganisasjonsledd shouldNotBe null
            }

            it("Deserializes Organisasjon with organisasjonsledd") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("987926279.json")
                organization?.organisasjonsnummer shouldBe "987926279"
                organization?.inngaarIJuridiskEnheter shouldBe null
                organization?.driverVirksomheter shouldBe null
                organization?.bestaarAvOrganisasjonsledd shouldNotBe null
            }
        }

        describe("orgnummerSet") {
            it("Fetches all orgnummer for organisasjon, including juridiske enheter and organisasjonsledd") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("314602374.json")
                val orgnummerSet = organization?.orgnummerSet()
                orgnummerSet shouldNotBe null
                orgnummerSet!!.sorted() shouldBe listOf("314602374", "310525790", "210259902").sorted()
            }

            it("Fetches all orgnummer for organisasjon with nested organisasjonsledd, including juridiske enheter and organisasjonsledd") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("987926279.json")
                val orgnummerSet = organization?.orgnummerSet()
                orgnummerSet shouldNotBe null
                orgnummerSet!!.sorted() shouldBe listOf(
                    "987926279",
                    "991076573",
                    "991012206",
                    "889640782",
                    "983887457"
                ).sorted()
            }
        }
    })
