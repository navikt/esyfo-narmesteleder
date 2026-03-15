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
    })
