package no.nav.syfo.narmesteleder.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class EmailAddressTest :
    DescribeSpec({
        describe("splitEmailAddresses") {
            it("keeps a single address") {
                "leder@example.com".splitEmailAddresses() shouldBe listOf("leder@example.com")
            }

            it("splits comma-separated addresses") {
                "leder@example.com,annen@example.com".splitEmailAddresses() shouldBe
                    listOf("leder@example.com", "annen@example.com")
            }

            it("splits semicolon-separated addresses") {
                "leder@example.com;annen@example.com".splitEmailAddresses() shouldBe
                    listOf("leder@example.com", "annen@example.com")
            }

            it("splits a mixture of separators") {
                "leder@example.com,annen@example.com;tredje@example.com".splitEmailAddresses() shouldBe
                    listOf("leder@example.com", "annen@example.com", "tredje@example.com")
            }

            it("trims whitespace around addresses") {
                " leder@example.com , annen@example.com ".splitEmailAddresses() shouldBe
                    listOf("leder@example.com", "annen@example.com")
            }

            it("filters empty entries after trailing separators") {
                "leder@example.com,;".splitEmailAddresses() shouldBe listOf("leder@example.com")
            }

            it("returns an empty list for an empty string") {
                "".splitEmailAddresses() shouldBe emptyList()
            }
        }
    })
