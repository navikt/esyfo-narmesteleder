package no.nav.syfo.integration.ereg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.syfo.platform.local.JsonFixtureLoader

class OrganisasjonTest :
    FunSpec({
        val fixtureLoader = JsonFixtureLoader("classpath:fake-clients/ereg")

        context("Successfully deserializes from the Ereg API response") {
            test("Deserializes Organisasjon with organisasjonsledd") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("314602374.json")
                organization?.organisasjonsnummer shouldBe "314602374"
                organization?.inngaarIJuridiskEnheter shouldBe null
                organization?.driverVirksomheter shouldBe null
                organization?.bestaarAvOrganisasjonsledd shouldNotBe null
            }
            test("Deserializes Organisasjon with nested organisasjonsledd") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("987926279.json")
                organization?.organisasjonsnummer shouldBe "987926279"
                organization?.inngaarIJuridiskEnheter shouldBe null
                organization?.driverVirksomheter shouldBe null
                organization?.bestaarAvOrganisasjonsledd shouldNotBe null
            }
        }

        context("getForetrukketNavn") {
            test("getForetrukketNavn returns sammensattnavn if available") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("314602374.json")
                organization?.getForetrukketNavn() shouldBe "MUNTER GREI PIGGSVIN"
            }
            test("getForetrukketNavn returns navnlinje1 if sammensattnavn is not available") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("987926279.json")
                organization?.getForetrukketNavn() shouldBe "NAV REGISTERFORVALTNING"
            }
        }
        context("aggregerOrgnummereFraHierarki") {
            test("Fetches all orgnummer for organisasjon, including juridiske enheter and organisasjonsledd") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("314602374.json")
                val orgnummerSet = organization?.aggregerOrgnummereFraHierarki()
                orgnummerSet shouldNotBe null
                orgnummerSet!!.sorted() shouldBe listOf("314602374", "310525790", "210259902").sorted()
            }

            test("Fetches all orgnummer for organisasjon with nested organisasjonsledd, including juridiske enheter and organisasjonsledd") {
                val organization = fixtureLoader.loadOrNull<Organisasjon>("987926279.json")
                val orgnummerSet = organization?.aggregerOrgnummereFraHierarki()
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

        context("orgnummerSet edge cases") {
            test("Returns only own orgnummer for a simple organisasjon without juridiske enheter and organisasjonsledd") {
                val organization = Organisasjon(
                    organisasjonsnummer = "100000001",
                )

                organization.aggregerOrgnummereFraHierarki() shouldBe setOf("100000001")
            }

            test("Returns own orgnummer and juridiske enheter when organisasjonsledd is missing") {
                val organization = Organisasjon(
                    organisasjonsnummer = "100000002",
                    inngaarIJuridiskEnheter = listOf(
                        Organisasjon(organisasjonsnummer = "200000001"),
                        Organisasjon(organisasjonsnummer = "200000002"),
                    ),
                )

                organization.aggregerOrgnummereFraHierarki() shouldBe setOf("100000002", "200000001", "200000002")
            }

            test("Includes organisasjonsledd orgnummer when organisasjonsledd has no juridiske enheter") {
                val organization = Organisasjon(
                    organisasjonsnummer = "100000003",
                    bestaarAvOrganisasjonsledd = listOf(
                        OrganisasjonsLeddWrapper(
                            organisasjonsledd = OrganisasjonsLedd(
                                organisasjonsnummer = "300000001",
                            ),
                        ),
                    ),
                )

                organization.aggregerOrgnummereFraHierarki() shouldBe setOf("100000003", "300000001")
            }

            test("Includes all juridiske enheter from an organisasjonsledd with multiple juridiske enheter") {
                val organization = Organisasjon(
                    organisasjonsnummer = "100000004",
                    bestaarAvOrganisasjonsledd = listOf(
                        OrganisasjonsLeddWrapper(
                            organisasjonsledd = OrganisasjonsLedd(
                                organisasjonsnummer = "300000002",
                                inngaarIJuridiskEnheter = listOf(
                                    Organisasjon(organisasjonsnummer = "200000003"),
                                    Organisasjon(organisasjonsnummer = "200000004"),
                                ),
                            ),
                        ),
                    ),
                )

                organization.aggregerOrgnummereFraHierarki() shouldBe setOf(
                    "100000004",
                    "300000002",
                    "200000003",
                    "200000004"
                )
            }

            test("Includes orgnummer from multiple organisasjonsledd on the same level") {
                val organization = Organisasjon(
                    organisasjonsnummer = "100000005",
                    bestaarAvOrganisasjonsledd = listOf(
                        OrganisasjonsLeddWrapper(
                            organisasjonsledd = OrganisasjonsLedd(
                                organisasjonsnummer = "300000003",
                            ),
                        ),
                        OrganisasjonsLeddWrapper(
                            organisasjonsledd = OrganisasjonsLedd(
                                organisasjonsnummer = "300000004",
                            ),
                        ),
                    ),
                )

                organization.aggregerOrgnummereFraHierarki() shouldBe setOf("100000005", "300000003", "300000004")
            }

            test("Returns only own orgnummer when bestaarAvOrganisasjonsledd is empty list or null") {
                val organizationWithEmptyList = Organisasjon(
                    organisasjonsnummer = "100000006",
                    bestaarAvOrganisasjonsledd = emptyList(),
                )
                val organizationWithNull = Organisasjon(
                    organisasjonsnummer = "100000007",
                    bestaarAvOrganisasjonsledd = null,
                )

                organizationWithEmptyList.aggregerOrgnummereFraHierarki() shouldBe setOf("100000006")
                organizationWithNull.aggregerOrgnummereFraHierarki() shouldBe setOf("100000007")
            }
        }
    })
